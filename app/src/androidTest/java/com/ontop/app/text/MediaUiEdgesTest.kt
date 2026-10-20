package com.ontop.app.text

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaUiEdgesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun externalFallbackUsesCanonicalYouTubeLinkWithStartTimeAndHandlesMissingBrowser() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        var launched: android.content.Intent? = null
        var fail = false
        val context = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: android.content.Intent) {
                if (fail) throw android.content.ActivityNotFoundException()
                launched = intent
            }
        }
        val video = YouTubeLink.parse("https://youtube.com/embed/M7lc1UVf-VE?start=87")!!
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { OnTopTheme { OpenVideoLink(video) } } }
        compose.onNodeWithText("Открыть в YouTube или браузере").performClick()
        assertEquals(android.content.Intent.ACTION_VIEW, launched!!.action)
        assertEquals("https://www.youtube.com/watch?v=M7lc1UVf-VE&t=87s", launched!!.dataString)
        fail = true
        compose.onNodeWithText("Открыть в YouTube или браузере").performClick()
        compose.onNodeWithText("Нет приложения для открытия ссылки. ${video.watchUrl}").assertIsDisplayed()
    }
    @Test fun pastedUrlIsOnlyConvertedOnRequestAndAdjacentStyledTextSurvives() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        try {
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            compose.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Ссылка")
            val body = compose.onNodeWithTag("editor-description")
            body.performTextInput("left right")
            body.performTextInputSelection(TextRange(0, 10))
            compose.onNodeWithContentDescription("Жирный").performScrollTo().performClick()
            body.performTextInputSelection(TextRange(4))
            val url = "https://www.youtube.com/shorts/M7lc1UVf-VE?t=42s"
            body.performTextInput(url)
            body.assertTextContains(url, substring = true)
            compose.onAllNodesWithText("Удалить видео из текста").assertCountEquals(0)
            compose.onNodeWithText("Преобразовать ссылку в видео").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val card = db.cardDao().observeCards().first().single()
            val document = RichTextCodec.decode(card.description, card.formatting)
            assertEquals("left right", card.description)
            assertTrue(document.has(RichKind.BOLD, 0, 4))
            assertTrue(document.has(RichKind.BOLD, 4, 10))
            val linked = document.videoToLink(document.media.single().id)
            assertEquals("left\n${url} right", linked.text)
            assertEquals(url, youtubeLinks(linked.text).single().video.source)
            assertEquals(42, document.media.single().video!!.seconds)
        } finally { model.viewModelScope.cancel(); db.close() }
    }
    @Test fun missingPrivateImageHasReadablePlaceholderAndFullScreenCanClose() {
        compose.setContent { OnTopTheme { NoteMediaView(NoteMedia("missing", 0, MediaKind.IMAGE,
            "images/00000000-0000-0000-0000-000000000000.jpg")) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Изображение недоступно").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("inline-image-missing").performClick()
        compose.onNodeWithTag("full-note-image").assertIsDisplayed()
        compose.onNodeWithText("Закрыть изображение").performClick()
        compose.onNodeWithTag("full-note-image").assertDoesNotExist()
    }
}
