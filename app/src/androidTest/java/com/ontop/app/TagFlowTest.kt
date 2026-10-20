package com.ontop.app

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.backup.BackupManager
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class TagFlowTest(private val width: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "portrait-{0}dp")
        fun widths() = listOf(arrayOf(360), arrayOf(393))
    }
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    private inner class Fixture(seedCards: Boolean) : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "tag-ui-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        val work = runBlocking { db.tagDao().create("Работа") }
        val home = runBlocking { db.tagDao().create("Личное") }
        val empty = runBlocking { db.tagDao().create("Пустая") }
        val model: BoardViewModel
        init {
            if (seedCards) runBlocking {
                db.cardDao().insert(CardEntity(1, "Первая", size = CardSize.WIDE, createdAt = 1, updatedAt = 3, sortOrder = 0, importance = 20))
                db.cardDao().insert(CardEntity(2, "Вторая", size = CardSize.WIDE, createdAt = 2, updatedAt = 2, sortOrder = 1, importance = 100, progress = 100))
                db.tagDao().replaceForCard(1, listOf(work, home), emptyList())
                db.tagDao().replaceForCard(2, listOf(work), emptyList())
            }
            lateinit var created: BoardViewModel
            instrumentation.runOnMainSync { created = BoardViewModel(app, db,
                BackupManager(app, db, root = root, refreshWidgets = {}), BoardPreferences(app, name)) }
            model = created
        }
        override fun close() {
            model.viewModelScope.cancel(); db.close(); app.deleteSharedPreferences(name); root.deleteRecursively()
        }
    }

    private fun show(f: Fixture): StateRestorationTester {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val owner = remember { object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            } }
            OnTopTheme { Box(Modifier.width(width.dp).fillMaxHeight()) { BoardScreen(f.model, owner.lifecycle) } }
        }
        compose.waitUntil(5000) { !f.model.state.value.loading }
        return tester
    }
    private fun topics() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(10_000) {
            if (compose.onAllNodesWithText("Темы").fetchSemanticsNodes().size == 1) true
            else {
                // IME may appear after performTextInput; wait for and open its compact settings row.
                if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("editor-settings-compact").performClick()
                }
                false
            }
        }
        compose.onNodeWithText("Темы").performClick()
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureTags") != "true") return
        compose.waitForIdle()
        val folder = File(app.cacheDir, "tag-check").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        try { File(folder, "$name-$width.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun editorDraftCreatesNoOrphanOnCancelAndSavesSeveralTopicsWithNote() = runBlocking<Unit> {
        Fixture(false).use { f ->
            val tester = show(f)
            fun start() {
                compose.onNodeWithContentDescription("Создать карточку").performClick()
                compose.onNodeWithTag("editor-title").performTextInput("Заметка с темами")
                topics()
            }
            start()
            compose.onNodeWithTag("tag-query").performTextInput("РаБ")
            compose.onNodeWithTag("tag-option-${f.work}").performClick()
            compose.onNodeWithTag("tag-query").performTextReplacement("Новая тема")
            compose.onNodeWithTag("tag-create-draft").performClick()
            tester.emulateSavedInstanceStateRestore()
            compose.onNodeWithContentDescription("Убрать тему Новая тема").assertExists()
            assertEquals(3, f.db.tagDao().all().size)
            assertTrue(f.db.cardDao().allCards().isEmpty())
            capture("editor-draft")
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertTrue(f.db.cardDao().allCards().isEmpty())
            assertEquals(3, f.db.tagDao().all().size)
            start()
            compose.onNodeWithTag("tag-option-${f.work}").performClick()
            compose.onNodeWithTag("tag-option-${f.home}").performClick()
            compose.onNodeWithTag("tag-query").performTextInput("Новая тема")
            compose.onNodeWithTag("tag-create-draft").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5000) { f.model.state.value.cards.size == 1 && !f.model.state.value.saving }
            val id = f.db.cardDao().allCards().single().id
            assertEquals(3, f.db.tagDao().forCard(id).size)
            assertEquals(4, f.db.tagDao().all().size)
            compose.onNodeWithTag("card-$id").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            topics()
            compose.onNodeWithContentDescription("Убрать тему Работа").performClick()
            compose.onNodeWithContentDescription("Убрать тему Новая тема").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.cardTags.size == 1 }
            assertEquals(listOf(f.home), f.db.tagDao().forCard(id))
        }
    }

    @Test fun filterSortSliderRenameDeleteAndEmptyTopicUseLiveRoomData() = runBlocking<Unit> {
        Fixture(true).use { f ->
            val tester = show(f)
            fun filter(id: Long) = compose.onNodeWithTag("topic-$id").performScrollTo().performClick()
            fun manage() = compose.onNodeWithContentDescription("Управление темами").performClick()
            filter(f.work)
            compose.onNodeWithTag("board-sort").performClick()
            compose.onNodeWithTag("board-sort-IMPORTANCE").performClick()
            compose.waitUntil(5000) { f.model.state.value.sortMode == BoardSort.IMPORTANCE }
            val first = compose.onNodeWithTag("card-2").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("card-1").fetchSemanticsNode().boundsInRoot
            assertTrue(first.top < second.top)
            compose.onNodeWithTag("board-sort").performClick()
            compose.onNodeWithTag("board-sort-PROGRESS").performClick()
            compose.waitUntil(5000) { f.model.state.value.sortMode == BoardSort.PROGRESS }
            f.db.cardDao().updatePercentages(1, 20, 100, 10)
            compose.waitUntil(5000) { f.model.state.value.cards.any { it.id == 1L && it.progress == 100 } }
            val updatedFirst = compose.onNodeWithTag("card-1").fetchSemanticsNode().boundsInRoot
            val updatedSecond = compose.onNodeWithTag("card-2").fetchSemanticsNode().boundsInRoot
            assertTrue(updatedFirst.top < updatedSecond.top)
            filter(f.home)
            tester.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("topic-${f.home}").assertIsSelected()
            compose.onNodeWithTag("card-2").assertDoesNotExist()
            compose.onNodeWithTag("card-1").assertIsDisplayed()
            compose.onNodeWithContentDescription("Просмотр заметок").performClick()
            compose.onNodeWithTag("slide-1").assertIsDisplayed()
            compose.onNodeWithTag("slide-2").assertDoesNotExist()
            compose.onNodeWithText("Закрыть").performClick()
            filter(f.empty)
            compose.onNodeWithText("В теме «Пустая» пока нет заметок").assertIsDisplayed()
            compose.onNodeWithText("Показать все заметки").performClick()
            filter(f.home)
            manage()
            compose.onNodeWithTag("tag-manager-name").performTextInput("Общая")
            compose.onNodeWithTag("tag-manager-save").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.tags.any { it.name == "Общая" } }
            compose.onNodeWithContentDescription("Переименовать тему Личное").performClick()
            compose.onNodeWithTag("tag-manager-name").performTextReplacement("Личные мысли")
            compose.onNodeWithTag("tag-manager-save").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.tags.any { it.id == f.home && it.name == "Личные мысли" } }
            assertEquals(f.home, f.model.state.value.selectedTagId)
            compose.onNodeWithTag("tag-manager-name").performTextInput("  РАБОТА ")
            compose.onNodeWithTag("tag-manager-save").assertIsNotEnabled()
            compose.onNodeWithText("Тема с таким названием уже существует").assertIsDisplayed()
            capture("manager")
            compose.onNodeWithContentDescription("Удалить тему Работа").performClick()
            compose.onNodeWithText("Удалить тему «Работа»?").assertIsDisplayed()
            compose.onNodeWithText("Удалить тему").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.tags.none { it.id == f.work } }
            assertEquals(2, f.db.cardDao().allCards().size)
            compose.onNodeWithContentDescription("Удалить тему Личные мысли").performClick()
            compose.onNodeWithText("Удалить тему").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.selectedTagId == null }
            compose.onNodeWithContentDescription("Удалить тему Пустая").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.tags.none { it.id == f.empty } }
            compose.onNodeWithContentDescription("Удалить тему Общая").performClick()
            compose.waitUntil(5000) { !f.model.state.value.saving && f.model.state.value.tags.isEmpty() }
            compose.onNodeWithText("Закрыть").performClick()
            compose.onNodeWithTag("topic-all").assertIsSelected()
            assertEquals(listOf(0L, 1L), f.db.cardDao().allCards().map { it.sortOrder })
            assertEquals(listOf(20, 100), f.db.cardDao().allCards().map { it.importance })
            capture("board")
        }
    }
}
