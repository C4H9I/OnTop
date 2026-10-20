package com.ontop.app

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotePercentagesFlowTest {
    @get:Rule val compose = createComposeRule()
    private fun field(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun awaitClosed() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("percent-save").fetchSemanticsNodes().isEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "status-flow").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun tileDetailsReviewAndEditorPersistCorrectNoteWithoutChangingStatusOrReminder() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        var model: BoardViewModel? = null
        val first = CardEntity(1, "Первая", createdAt = 1, updatedAt = 1, sortOrder = 0)
        val second = CardEntity(2, "Вторая", size = CardSize.WIDE, createdAt = 2, updatedAt = 2, sortOrder = 1, progress = 0)
        try {
            db.cardDao().insert(first); db.cardDao().insert(second)
            db.reminderDao().insert(ReminderEntity(1, 1, 9, 0, false, 1, 1))
            val reminder = db.reminderDao().forCard(1)!!
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            compose.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model!!, owner.lifecycle) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("tile-status-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tile-status-1", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("details-status-1").assertDoesNotExist()
            field("importance-input", "75")
            compose.onNodeWithTag("progress-tracking").performScrollTo().performClick()
            field("progress-input", "50")
            compose.onNodeWithTag("percent-save").performClick(); awaitClosed()
            assertEquals(75, db.cardDao().findById(1)!!.importance)
            capture("board")
            compose.onNodeWithTag("card-1").performClick()
            compose.onNodeWithTag("details-status-1").performScrollTo().performClick()
            field("progress-input", "100")
            compose.onNodeWithTag("percent-save").performClick(); awaitClosed()
            assertEquals(CardStatus.ACTIVE, db.cardDao().findById(1)!!.status)
            assertEquals(reminder, db.reminderDao().forCard(1))
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            compose.onNodeWithContentDescription("Просмотр заметок").performClick()
            compose.onNodeWithTag("review-status-1").assertExists().performClick()
            compose.onNodeWithTag("progress-input").assertTextContains("100")
            field("importance-input", "99")
            compose.onNodeWithTag("percent-save").performClick(); awaitClosed()
            compose.onNodeWithTag("review-status-1").assertContentDescriptionContains("Важность: 99%. Готовность: 100%")
            capture("review")
            compose.onNodeWithText("Далее").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-status-2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("review-status-2").performClick()
            compose.onNodeWithTag("progress-input").assertTextContains("0")
            field("importance-input", "1")
            compose.onNodeWithTag("progress-tracking").performScrollTo().performClick()
            compose.onNodeWithTag("percent-save").performClick(); awaitClosed()
            assertNull(db.cardDao().findById(2)!!.progress)
            assertEquals(99, db.cardDao().findById(1)!!.importance)
            assertEquals(1, db.cardDao().findById(2)!!.importance)
            compose.onNodeWithText("Назад", substring = false).performClick()
            compose.onNodeWithTag("review-status-1").assertExists()
            compose.onNodeWithText("Закрыть", substring = false).performClick()
            compose.onNodeWithTag("card-1").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("editor-settings-compact").performClick()
            compose.onNodeWithText("Другие настройки").performClick()
            compose.onNodeWithText("Важность и готовность · 100%").performScrollTo().performClick()
            field("importance-input", "0")
            field("progress-input", "0")
            capture("editor")
            compose.onNodeWithTag("percent-save").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertEquals(99, db.cardDao().findById(1)!!.importance)
            assertEquals(100, db.cardDao().findById(1)!!.progress)
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithText("Другие настройки").performClick()
            compose.onNodeWithText("Важность и готовность · 100%").performScrollTo().performClick()
            field("progress-input", "50")
            compose.onNodeWithTag("percent-save").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            assertEquals(50, db.cardDao().findById(1)!!.progress)
            assertEquals(reminder, db.reminderDao().forCard(1))
        } finally { model?.viewModelScope?.cancel(); db.close() }
    }
}
