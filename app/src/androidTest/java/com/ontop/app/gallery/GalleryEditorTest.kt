package com.ontop.app.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.CardEditorScreen
import com.ontop.app.data.*
import com.ontop.app.text.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class GalleryEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun galleryCameraAndFileUseExistingInlineImportAndKeepCoverSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = CardImageStore(context)
        val paths = mutableSetOf<String>()
        val source = File(context.cacheDir, "shared-notes/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(90, 120, Bitmap.Config.ARGB_8888).apply {
            source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
        }
        val photo = FileProvider.getUriForFile(context, "${context.packageName}.shares", source)
        var cameraLaunched = false; var fileLaunched = false; var saved: RichDocument? = null
        var coverChanged = false
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                val output: Any = when (contract) {
                    is TakeNotePhoto -> {
                        cameraLaunched = true
                        val intent = contract.createIntent(context, input as Uri)
                        assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
                        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                        context.contentResolver.openOutputStream(input)!!.use { it.write(source.readBytes()) }
                        true
                    }
                    is ActivityResultContracts.OpenMultipleDocuments -> { fileLaunched = true; listOf(photo) }
                    is ActivityResultContracts.PickVisualMedia -> photo
                    is ActivityResultContracts.RequestMultiplePermissions -> emptyMap<String, Boolean>()
                    else -> error("Unexpected contract ${contract.javaClass}")
                }
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, output) }
            }
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) { OnTopTheme {
                    CardEditorScreen(CardEntity(id = 1, title = "Gallery test", createdAt = 1, updatedAt = 1, sortOrder = 0),
                        saving = false, error = null, imagePath = null,
                        onImageSelected = { coverChanged = true }, onRemoveImage = {}, onPickerFailed = { fail("Picker failed") },
                        onDismiss = {}, onSave = { _, _, _ -> }, onDelete = {},
                        onInlineImport = { uri, done -> runBlocking {
                            val path = images.import(uri); paths.add(path); done(path)
                        } }, onCameraImport = { name, done -> runBlocking {
                            val captures = CameraCaptureStore(context)
                            val path = images.import(captures.uri(name)); paths.add(path); captures.delete(name); done(path)
                        } }, onAttachFile = { uris, done -> runBlocking {
                            done(uris.map { AttachmentStore(context).import(it).also { paths.add(it.path!!) } })
                        } }, onSaveFormatted = { _, text, _, formatting -> saved = RichTextCodec.decode(text, formatting) })
                } }
            }
            fun gallery() {
                val button = compose.onNodeWithText("Фото +")
                if (compose.onAllNodesWithTag("format-toolbar").fetchSemanticsNodes().isNotEmpty()) button.performScrollTo()
                button.performClick()
                compose.onNodeWithTag("gallery-sheet").assertIsDisplayed()
            }
            gallery()
            compose.onNodeWithTag("gallery-camera").performClick()
            compose.waitUntil(5000) { cameraLaunched && compose.onAllNodesWithTag("gallery-sheet").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("editor-save").performClick()
            compose.runOnIdle { assertEquals(MediaKind.IMAGE, saved!!.media.single().kind) }
            // Open the inserted image using the unchanged graphical editor entry point.
            val inserted = saved!!.media.single()
            compose.onNodeWithTag("inline-image-${inserted.id}").performScrollTo().performClick()
            compose.onNodeWithText("Редактировать изображение").performClick()
            compose.onNodeWithTag("drawing-canvas").assertIsDisplayed()
            compose.onNodeWithText("Назад").performClick()
            gallery()
            compose.onNodeWithTag("gallery-file-tab").performClick()
            compose.waitUntil(5000) { fileLaunched }
            compose.onNodeWithTag("editor-save").performClick()
            compose.runOnIdle {
                assertEquals(2, saved!!.media.size)
                assertEquals(1, saved!!.media.count { it.kind == MediaKind.FILE })
                assertFalse(coverChanged)
            }
        } finally { paths.forEach { images.delete(it) }; source.delete() }
    }
}
