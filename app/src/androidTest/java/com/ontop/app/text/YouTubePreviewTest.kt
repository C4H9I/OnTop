package com.ontop.app.text

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class YouTubePreviewTest {
    @get:Rule val compose = createComposeRule()
    private val video = YouTubeLink.parse("https://youtu.be/M7lc1UVf-VE?t=12")!!
    private fun loader(image: Bitmap? = null, title: String? = null) = object : YouTubePreviewLoader {
        override suspend fun thumbnail(id: String) = image
        override suspend fun title(id: String) = title
    }
    private fun record(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("captureYouTubePreview") == "true") {
            val directory = File(instrumentation.targetContext.cacheDir, "youtube-preview-check").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { stream ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
    }
    @Test fun narrowPreviewKeepsRatioInBothThemesAndEditorMenuDeletesOnlyOnRequest() {
        var dark by mutableStateOf(false)
        var deleted = 0
        val image = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff55705b.toInt()) }
        val loader = loader(image, "Важные мысли — время остановиться")
        compose.setContent { CompositionLocalProvider(LocalYouTubePreviewLoader provides loader) {
            OnTopTheme(darkTheme = dark) { Surface { Column(Modifier.width(280.dp).padding(8.dp)) {
                Text("Текст перед видео")
                NoteMediaView(NoteMedia("one", 0, MediaKind.YOUTUBE, video = video), onRemove = { deleted++ })
                Text("Текст после видео")
            } } }
        } }
        compose.onNodeWithText("Важные мысли — время остановиться").assertIsDisplayed()
        val preview = compose.onNodeWithTag("youtube-preview-${video.id}")
        val bounds = preview.fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, bounds.width / bounds.height, .02f)
        compose.onNodeWithText("Обновить обложку").assertDoesNotExist()
        compose.onNodeWithText("Открыть в YouTube или браузере").assertDoesNotExist()
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
        record("preview-light-narrow")
        compose.runOnIdle { dark = true }
        record("preview-dark-narrow")
        compose.onNodeWithContentDescription("Действия с видео").performClick()
        compose.onNodeWithText("Обновить обложку").assertIsDisplayed()
        compose.onNodeWithText("Открыть в YouTube").assertIsDisplayed()
        compose.onNodeWithText("Удалить видео").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
        compose.onNodeWithText("Текст перед видео").assertIsDisplayed()
        compose.onNodeWithText("Текст после видео").assertIsDisplayed()
    }
    @Test fun offlineMultipleVideosHaveNoEditorActionsAndOnlyRequestedPlayerExists() {
        var active by mutableStateOf(true)
        val loader = loader()
        val document = RichDocument("До\nМежду\nПосле", media = listOf(
            NoteMedia("first", 3, MediaKind.YOUTUBE, video = video),
            NoteMedia("second", 9, MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ"))))
        compose.setContent { CompositionLocalProvider(LocalYouTubePreviewLoader provides loader) {
            OnTopTheme { Surface { Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                MultimediaNoteView(document, active)
            } } }
        } }
        compose.onAllNodesWithText("Обложка недоступна · проверьте интернет").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Действия с видео").assertCountEquals(0)
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
        val preview = compose.onNodeWithTag("youtube-preview-${video.id}")
        preview.performScrollTo()
        val bounds = preview.fetchSemanticsNode().boundsInRoot
        record("preview-offline-multiple")
        preview.performClick()
        compose.onNodeWithTag("youtube-player").assertIsDisplayed()
        compose.onNodeWithText("Закрыть видео").performClick()
        assertEquals(bounds, preview.fetchSemanticsNode().boundsInRoot)
        preview.performClick()
        compose.runOnIdle { active = false }
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
    }
    @Test fun pendingPreviewShowsProgressAndPlayRemainsAvailableWithoutMetadata() {
        val loader = object : YouTubePreviewLoader {
            override suspend fun thumbnail(id: String): Bitmap? = awaitCancellation()
            override suspend fun title(id: String): String? = null
        }
        var plays = 0
        compose.setContent { CompositionLocalProvider(LocalYouTubePreviewLoader provides loader) {
            OnTopTheme { YouTubePreview(video, Modifier.width(280.dp), onPlay = { plays++ }) }
        } }
        compose.onNodeWithTag("youtube-preview-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Обложка недоступна · проверьте интернет").assertDoesNotExist()
        compose.onNodeWithTag("youtube-preview-${video.id}").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun compactExternalActionPreservesStartTimeAndReportsMissingApplication() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        var launched: android.content.Intent? = null
        var fail = false
        val context = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: android.content.Intent) {
                if (fail) throw android.content.ActivityNotFoundException()
                launched = intent
            }
        }
        val loader = loader()
        compose.setContent { CompositionLocalProvider(LocalContext provides context,
            LocalYouTubePreviewLoader provides loader) {
            OnTopTheme { YouTubePreview(video, Modifier.width(280.dp), onPlay = {}) }
        } }
        compose.onNodeWithContentDescription("Открыть видео в YouTube").performClick()
        assertEquals("https://www.youtube.com/watch?v=M7lc1UVf-VE&t=12s", launched!!.dataString)
        fail = true
        compose.onNodeWithContentDescription("Открыть видео в YouTube").performClick()
        compose.onNodeWithText("Нет приложения для открытия ссылки. ${video.watchUrl}").assertIsDisplayed()
    }
}
