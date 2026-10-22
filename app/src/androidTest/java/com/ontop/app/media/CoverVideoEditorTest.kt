package com.ontop.app.media

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.gallery.*
import com.ontop.app.text.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CoverVideoEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sharedGalleryRoutesCoverPhotoCoverCameraAndVideoToTheirDestinations() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = CardImageStore(context)
        val captures = CameraCaptureStore(context)
        val source = File(context.cacheDir, "shared-notes/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
        }
        val photo = FileProvider.getUriForFile(context, "${context.packageName}.shares", source)
        val owned = mutableSetOf<String>()
        var cover by mutableStateOf<String?>(null)
        var saved: RichDocument? = null
        var videos = 0; var photos = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val result: Any = when (contract) {
                    is TakeNotePhoto -> {
                        photos++
                        context.contentResolver.openOutputStream(input as Uri)!!.use { it.write(source.readBytes()) }; true
                    }
                    is CaptureNoteVideo -> {
                        videos++
                        assertEquals(android.provider.MediaStore.ACTION_VIDEO_CAPTURE, contract.createIntent(context, input as Uri).action)
                        context.contentResolver.openOutputStream(input)!!.use { output -> instrumentation.context.assets.open("media/sample.mp4").use { it.copyTo(output) } }
                        true
                    }
                    is ActivityResultContracts.OpenDocument -> photo
                    is ActivityResultContracts.PickVisualMedia -> photo
                    is ActivityResultContracts.RequestMultiplePermissions -> emptyMap<String, Boolean>()
                    else -> error("Unexpected ${contract.javaClass}")
                }
                Handler(Looper.getMainLooper()).post { dispatchResult(code, result) }
            }
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { OnTopTheme {
                CardEditorScreen(CardEntity(id = 1, title = "Cover and video", createdAt = 1, updatedAt = 1, sortOrder = 0),
                    saving = false, error = null, imagePath = cover,
                    onImageSelected = { uri -> runBlocking { cover = store.import(uri).also { owned.add(it) } } },
                    onRemoveImage = { cover = null }, onPickerFailed = { fail("Picker failed") }, onDismiss = {},
                    onSave = { _, _, _ -> }, onDelete = {}, onInlineImport = { _, _ -> fail("Cover inserted into text") },
                    onCameraCoverImport = { name -> runBlocking {
                        cover = store.import(captures.uri(name)).also { owned.add(it) }; captures.delete(name)
                    } }, onCapturedMediaImport = { name, done -> runBlocking {
                        val block = AttachmentStore(context).import(captures.uri(name)); owned.add(block.path!!); captures.delete(name); done(block)
                    } }, onSaveFormatted = { _, text, _, formatting -> saved = RichTextCodec.decode(text, formatting) })
            } } }
            compose.onNodeWithContentDescription("Выбрать обложку").performClick()
            compose.onNodeWithText("Обложка").assertIsDisplayed()
            compose.onNodeWithTag("gallery-file-tab").performClick()
            compose.waitUntil(5000) { cover != null }
            compose.onNodeWithTag("editor-save").performClick()
            compose.runOnIdle { assertTrue(saved!!.media.isEmpty()) }
            val firstCover = cover
            compose.onNodeWithContentDescription("Выбрать обложку").performClick()
            compose.onNodeWithTag("gallery-camera").performClick()
            compose.waitUntil(10000) { photos == 1 && cover != firstCover }
            compose.onNodeWithTag("editor-save").performClick()
            compose.runOnIdle { assertTrue(saved!!.media.isEmpty()) }
            compose.onNodeWithText("Видео +").performClick()
            compose.onNodeWithText("Снять видео").assertIsDisplayed()
            compose.onNodeWithTag("gallery-camera").performClick()
            compose.waitUntil(10000) { videos == 1 && compose.onAllNodesWithTag("gallery-sheet").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("editor-save").performClick()
            compose.runOnIdle { assertEquals(MediaKind.LOCAL_VIDEO, saved!!.media.single().kind); assertNotNull(cover) }
        } finally { owned.forEach { store.delete(it) }; source.delete() }
    }
}
