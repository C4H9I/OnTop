package com.ontop.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.backup.BackupManager
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NoteInteractionFlowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private inner class Fixture : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "interaction-flow-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        lateinit var model: BoardViewModel
        suspend fun start() {
            (1L..2).forEach { db.cardDao().insert(CardEntity(it, "Мысль $it", size = CardSize.WIDE,
                createdAt = it, updatedAt = it, sortOrder = it)) }
            instrumentation.runOnMainSync { model = BoardViewModel(app, db,
                BackupManager(app, db, root = root, refreshWidgets = {}), BoardPreferences(app, name)) }
            withTimeout(5000) { model.state.first { !it.loading } }
        }
        override fun close() { model.viewModelScope.cancel(); db.close(); app.deleteSharedPreferences(name); root.deleteRecursively() }
    }
    private suspend fun done(action: (() -> Unit) -> Unit) {
        val completed = AtomicBoolean(false)
        instrumentation.runOnMainSync { action { completed.set(true) } }
        withTimeout(5000) { while (!completed.get()) delay(10) }
    }

    @Test fun successfulMutationsCountActualChangesOnlyAndCreationIsNotEditing() = runBlocking {
        Fixture().use { f ->
            f.start()
            var card = f.db.cardDao().findById(1)!!
            done { f.model.saveNote(card, card.title, card.description, card.size, null, false, it) }
            assertEquals(0L, f.model.interactions.count(1, NoteInteractionType.NOTE_EDITED))
            done { f.model.savePercentages(1, 75, 50, it) }
            done { f.model.savePercentages(1, 75, 50, it) }
            assertEquals(1L, f.model.interactions.count(1, NoteInteractionType.NOTE_EDITED))
            card = f.db.cardDao().findById(1)!!
            val tag = f.db.tagDao().create("Тема")
            done { f.model.saveNote(card, "Изменено", "Текст 🧠", card.size, null, false, it, tagIds = listOf(tag)) }
            assertEquals(2L, f.model.interactions.count(1, NoteInteractionType.NOTE_EDITED))
            card = f.db.cardDao().findById(1)!!
            done { f.model.saveNote(card, card.title, card.description, card.size, null, false, it, tagIds = listOf(tag)) }
            assertEquals(2L, f.model.interactions.count(1, NoteInteractionType.NOTE_EDITED))
            repeat(2) { done { f.model.addComment(1, "Комментарий", it.let { success -> { _: Long -> success() } }) } }
            instrumentation.runOnMainSync { f.model.addComment(1, "  ") {} }
            assertEquals(2L, f.model.interactions.count(1, NoteInteractionType.COMMENT_ADDED))
            assertEquals(2L, f.model.interactions.count(1, NoteInteractionType.NOTE_EDITED))
            done { f.model.saveNote(null, "Новая", "", CardSize.NORMAL, null, false, it) }
            val created = f.db.cardDao().allCards().single { it.title == "Новая" }
            assertTrue(f.model.interactions.history(created.id).isEmpty())
            done { f.model.changeStatus(card, CardStatus.ARCHIVED, it) }
            assertEquals(4, f.model.interactions.history(1).size)
            done { f.model.changeStatus(card, CardStatus.TRASHED, it) }
            done { f.model.permanentlyDelete(card, it) }
            assertTrue(f.model.interactions.history(1).isEmpty())
        }
    }

    @Test fun boardSliderRestorationAndWidgetOpenHaveDistinctSources() = runBlocking {
        Fixture().use { f ->
            f.start()
            var requested by mutableStateOf<Long?>(null)
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(f.model, owner.lifecycle, requested,
                    onCardRequestHandled = { requested = null }, requestFromWidget = true) }
            }
            fun awaitCount(id: Long, type: NoteInteractionType, value: Long) = compose.waitUntil(5000) {
                runBlocking { f.model.interactions.count(id, type) == value }
            }
            compose.onNodeWithTag("card-1").performClick()
            awaitCount(1, NoteInteractionType.NOTE_OPENED, 1)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithTag("editor-title").performTextReplacement("Отменённый заголовок")
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertEquals(0L, f.model.interactions.count(1, NoteInteractionType.NOTE_EDITED))
            assertEquals("Мысль 1", f.db.cardDao().findById(1)!!.title)
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            compose.onNodeWithTag("card-1").performClick()
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            awaitCount(1, NoteInteractionType.NOTE_OPENED, 1)
            compose.onNodeWithContentDescription("Просмотр заметок").performClick()
            awaitCount(1, NoteInteractionType.SLIDER_VIEWED, 1)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Далее").performClick()
            awaitCount(2, NoteInteractionType.SLIDER_VIEWED, 1)
            compose.onNodeWithText("Назад").performClick()
            awaitCount(1, NoteInteractionType.SLIDER_VIEWED, 2)
            compose.onNodeWithText("Закрыть").performClick()
            compose.runOnIdle { requested = 2 }
            awaitCount(2, NoteInteractionType.WIDGET_OPENED, 1)
            restoration.emulateSavedInstanceStateRestore()
            assertEquals(0L, f.model.interactions.count(2, NoteInteractionType.NOTE_OPENED))
            assertEquals(1L, f.model.interactions.count(2, NoteInteractionType.WIDGET_OPENED))
        }
    }

    @Test fun queuedOpeningFromPreviousDatasetCannotBeAttachedAfterRestore() = runBlocking {
        Fixture().use { f ->
            f.start()
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = launch(Dispatchers.IO) { BackupManager.dataAccess { locked.complete(Unit); release.await() } }
            locked.await()
            try {
                instrumentation.runOnMainSync { f.model.recordOpening(1, NoteInteractionType.NOTE_OPENED) }
                f.model.backup.revision.value = "replaced-${System.nanoTime()}"
            } finally { release.complete(Unit) }
            blocker.join()
            BackupManager.dataAccess { assertEquals(0L, f.model.interactions.count(1, NoteInteractionType.NOTE_OPENED)) }
        }
    }
}
