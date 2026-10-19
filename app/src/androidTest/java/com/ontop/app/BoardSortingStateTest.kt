package com.ontop.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.backup.BackupManager
import com.ontop.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BoardSortingStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun modesPersistAcrossViewModelsWithoutAnyRoomWrites() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "board-preferences-test-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        val preferences = BoardPreferences(app, name)
        val backup = BackupManager(app, db, root = root, refreshWidgets = {})
        val models = mutableListOf<BoardViewModel>()
        fun model(): BoardViewModel {
            lateinit var result: BoardViewModel
            instrumentation.runOnMainSync { result = BoardViewModel(app, db, backup, BoardPreferences(app, name)) }
            return result.also { models += it }
        }
        try {
            assertEquals(BoardSort.MANUAL, preferences.loadSort())
            db.cardDao().insert(CardEntity(1, "A", createdAt = 1, updatedAt = 3, sortOrder = 0, importance = 0))
            db.cardDao().insert(CardEntity(2, "B", createdAt = 2, updatedAt = 2, sortOrder = 1, importance = 100))
            val original = db.cardDao().allCards()
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_sort_$operation BEFORE $operation ON cards BEGIN SELECT RAISE(ABORT, 'Sorting must not write cards'); END")
            var current = model()
            withTimeout(5000) { current.state.first { !it.loading } }
            for (mode in BoardSort.entries) {
                instrumentation.runOnMainSync { current.setBoardSort(mode) }
                withTimeout(5000) { current.state.first { it.sortMode == mode } }
                assertEquals(mode, preferences.loadSort())
                assertEquals(original, db.cardDao().allCards())
                assertNull(current.state.value.error)
            }
            current.viewModelScope.cancel()
            current = model()
            withTimeout(5000) { current.state.first { !it.loading } }
            assertEquals(BoardSort.UPDATED, current.state.value.sortMode)
            assertEquals(NoteSliderOrder.BOARD, current.sliderSession.order)
            current.viewModelScope.cancel()
            app.getSharedPreferences(name, 0).edit().putString("sort", "future-mode").commit()
            assertEquals(BoardSort.MANUAL, BoardPreferences(app, name).loadSort())
        } finally {
            models.forEach { it.viewModelScope.cancel() }; db.close()
            app.deleteSharedPreferences(name); root.deleteRecursively()
        }
    }

    @Test fun savedImportanceReordersLiveBoardAndDeletionKeepsManualPositions() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "board-live-test-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        lateinit var model: BoardViewModel
        var initialized = false
        try {
            val first = db.cardDao().insert(CardEntity(title = "A", createdAt = 1, updatedAt = 1, sortOrder = 0, importance = 0))
            val second = db.cardDao().insert(CardEntity(title = "B", createdAt = 2, updatedAt = 2, sortOrder = 1, importance = 100))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db,
                BackupManager(app, db, root = root, refreshWidgets = {}), BoardPreferences(app, name)) }
            initialized = true
            withTimeout(5000) { model.state.first { !it.loading } }
            instrumentation.runOnMainSync { model.setBoardSort(BoardSort.IMPORTANCE) }
            withTimeout(5000) { model.state.first { it.sortMode == BoardSort.IMPORTANCE } }
            assertEquals(second, sortedBoardCards(model.state.value.cards, model.state.value.sortMode).first().id)
            var saved = false
            instrumentation.runOnMainSync {
                model.beginImageEdit(null)
                model.saveNote(null, "Default", "", CardSize.NORMAL, null, false, { saved = true })
            }
            withTimeout(5000) { while (!saved) delay(20) }
            assertEquals(50, db.cardDao().allCards().single { it.title == "Default" }.importance)
            val original = db.cardDao().findById(first)!!
            saved = false
            instrumentation.runOnMainSync {
                model.beginImageEdit(original)
                model.saveNote(original, "A edited", "", original.size, null, false, { saved = true }, importance = 100)
            }
            withTimeout(5000) { model.state.first { !it.saving && it.cards.any { c -> c.id == first && c.importance == 100 } } }
            assertEquals(first, sortedBoardCards(model.state.value.cards, BoardSort.IMPORTANCE).first().id)
            val edited = db.cardDao().findById(first)!!
            assertEquals(original.createdAt, edited.createdAt)
            assertEquals(original.sortOrder, edited.sortOrder)
            assertTrue(edited.updatedAt > original.updatedAt)
            assertEquals(100, edited.importance)
            var deleted = false
            instrumentation.runOnMainSync { model.delete(edited) { deleted = true } }
            withTimeout(5000) { model.state.first { it.cards.none { c -> c.id == first } && !it.saving } }
            assertTrue(deleted)
            assertEquals(1L, db.cardDao().findById(second)!!.sortOrder)
        } finally {
            if (initialized) model.viewModelScope.cancel()
            db.close(); app.deleteSharedPreferences(name); root.deleteRecursively()
        }
    }
}
