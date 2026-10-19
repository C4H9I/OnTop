package com.ontop.app.text

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class YouTubePlayerTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun web(): WebView {
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        return WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<WebView>().single { it.isAttachedToWindow }
    }
    private fun record(name: String, status: String) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            File(output, "$name.txt").writeText(status)
            File(output, "$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun iframeUsesSafeWebViewAndNetworkFailureKeepsOriginalLinkAvailable() {
        val video = YouTubeLink.parse("https://youtu.be/M7lc1UVf-VE?t=12")!!
        compose.setContent { OnTopTheme { YouTubePlayerDialog(video) {} } }
        compose.onNodeWithTag("youtube-player").assertIsDisplayed()
        compose.runOnIdle {
            val web = web()
            assertFalse(web.settings.allowFileAccess); assertFalse(web.settings.allowContentAccess)
            assertTrue(web.settings.javaScriptEnabled); assertTrue(web.settings.mediaPlaybackRequiresUserGesture)
            assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
            web.stopLoading(); web.settings.blockNetworkLoads = true
            web.loadDataWithBaseURL("$PLAYER_ORIGIN/", playerHtml(video), "text/html", "UTF-8", null)
        }
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText("YouTube не отвечает. Проверьте интернет или откройте ссылку позже.").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Нет соединения с YouTube. Проверьте интернет или откройте ссылку позже.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Открыть в YouTube или браузере").assertIsDisplayed()
        record("youtube-offline", "WebView network blocked: offline message and external link remain available; source=${video.source}")
    }
    @Test fun attemptActualOnlinePlaybackAndRecordObservedResult() {
        compose.setContent { OnTopTheme { YouTubePlayerDialog(YouTubeLink.parse("https://www.youtube.com/watch?v=M7lc1UVf-VE")!!) {} } }
        compose.onNodeWithTag("youtube-player").assertIsDisplayed()
        // Real touch, not a script bypassing the user's playback gesture.
        compose.onNodeWithTag("youtube-player").performTouchInput { click(center) }
        runCatching { compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Нажмите ▶ в проигрывателе").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Воспроизведение").fetchSemanticsNodes().isNotEmpty()
        } }
        if (compose.onAllNodesWithText("Воспроизведение").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("youtube-player").performTouchInput { click(center) }
        runCatching { compose.waitUntil(15_000) { compose.onAllNodesWithText("Воспроизведение").fetchSemanticsNodes().isNotEmpty() } }
        val text = compose.onNodeWithTag("youtube-status").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        record("youtube-online-observation", "Actual device/network attempt. Observed: $text. PLAYING verified=${text == "Воспроизведение"}")
        compose.onNodeWithText("Открыть в YouTube или браузере").assertIsDisplayed()
    }
}
