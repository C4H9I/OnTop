package com.ontop.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ontop.app.data.CardEntity
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import com.ontop.app.text.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier

class NoteSliderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun boardSliderUsesExistingEditorAndComments() = kotlinx.coroutines.runBlocking<Unit> {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.ontop.app.data.OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        try {
            db.cardDao().insert(CardEntity(id = 1, title = "Исходная заметка", createdAt = 1, updatedAt = 1, sortOrder = 0))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            compose.setContent {
                val owner = remember { object : androidx.lifecycle.LifecycleOwner {
                    override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).apply {
                        currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                    }
                } }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Просмотр заметок").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithTag("editor-title").performTextReplacement("Изменённая заметка")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-slider").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Изменённая заметка") and hasAnyAncestor(hasTestTag("slide-1"))).assertExists()
            compose.onNodeWithText("Комментарии").performClick()
            compose.onNodeWithTag("history-heading").assertExists()
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            compose.onNodeWithTag("note-slider").assertExists()
        } finally {
            instrumentation.runOnMainSync { androidx.lifecycle.ViewModelStore().apply { put("test", model); clear() } }
            db.close()
        }
    }

    @Test fun multimediaFormattingAndInvisiblePlayerDisposal() {
        var active by mutableStateOf(true)
        val document = RichDocument("Жирный текст https://example.org", listOf(RichRange(0, 6, RichKind.BOLD)),
            listOf(NoteMedia("image-a", 0, MediaKind.IMAGE, path = "images/missing-a.jpg"),
                NoteMedia("image-b", 0, MediaKind.IMAGE, path = "images/missing-b.jpg"),
                NoteMedia("video", 0, MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtu.be/M7lc1UVf-VE"))))
        compose.setContent { OnTopTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            MultimediaNoteView(document, active)
        } } }
        compose.onAllNodes(inlineImages()).assertCountEquals(2)
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
        compose.onNodeWithTag("youtube-preview-M7lc1UVf-VE").performScrollTo().performClick()
        compose.onNodeWithTag("youtube-player").assertExists()
        compose.runOnIdle { active = false }
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("youtube-player").assertDoesNotExist()
    }

    @Test fun navigationMutationReopenAndEmptyState() {
        var cards by mutableStateOf((1L..25L).map { CardEntity(id = it, title = "Заметка $it",
            description = "Длинный текст\n".repeat(100), createdAt = it, updatedAt = it, sortOrder = it) })
        val session = NoteSliderSession().also { it.enter(cards) }
        var open by mutableStateOf(true)
        var edited: Long? = null
        var details: Long? = null
        compose.setContent { OnTopTheme { if (open) NoteSliderScreen(cards, session,
            { open = false }, { edited = it.id }, { details = it.id }) } }
        compose.onNodeWithText("1 из 25").assertIsDisplayed()
        compose.onNodeWithText("Порядок").performClick()
        compose.onNodeWithText("Дата создания · новые сначала").performClick()
        compose.onNodeWithText("25 из 25").assertIsDisplayed()
        compose.onNodeWithText("Порядок").performClick()
        compose.onNodeWithText("Порядок доски").performClick()
        compose.onNodeWithText("1 из 25").assertIsDisplayed()
        compose.onNodeWithText("Назад").assertIsNotEnabled()
        compose.onNodeWithText("Далее").performClick()
        compose.onNodeWithText("2 из 25").assertIsDisplayed()
        compose.onNodeWithTag("slide-2").performTouchInput { swipeUp() }
        compose.onNodeWithText("Редактировать").performClick()
        compose.onNodeWithText("Комментарии").performClick()
        compose.runOnIdle { assertEquals(2L, edited); assertEquals(2L, details) }
        compose.onNodeWithTag("slide-2").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 из 25").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithText("3 из 25").assertIsDisplayed()
        compose.runOnIdle { cards = cards.filter { it.id != 3L } }
        compose.onNodeWithText("3 из 24").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4L, session.currentId); cards = cards.take(2) }
        compose.onNodeWithText("2 из 2").assertIsDisplayed()
        compose.runOnIdle { cards = cards.take(1) }
        compose.onNodeWithText("1 из 1").assertIsDisplayed()
        compose.onNodeWithText("Далее").assertIsNotEnabled()
        compose.runOnIdle { cards = emptyList() }
        compose.onNodeWithText("Пока нет заметок").assertIsDisplayed()
    }
}
