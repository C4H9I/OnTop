package com.ontop.app

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
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

class ChecklistEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checklistCreationViewToggleAutomaticProgressAndCancelUseExistingRoutes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "checklist-ui-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        lateinit var model: BoardViewModel
        var modelCreated = false
        try {
            db.cardDao().insert(CardEntity(1, "Обычная", "Текст", createdAt = 1, updatedAt = 1, sortOrder = 0, progress = 42))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db,
                BackupManager(app, db, root = root, refreshWidgets = {}), BoardPreferences(app, name)) }
            modelCreated = true
            withTimeout(5000) { model.state.first { !it.loading } }
            compose.setContent { OnTopTheme { Box(Modifier.width(360.dp).fillMaxHeight()) {
                BoardScreen(model, LocalLifecycleOwner.current.lifecycle, null, {})
            } } }
            compose.onNodeWithContentDescription("Создать чек-лист").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Покупки")
            compose.onNodeWithTag("checklist-add-last").performScrollTo().performClick()
            compose.onNodeWithText("Пункт").performTextInput("Последний")
            compose.onNodeWithTag("checklist-add-first").performScrollTo().performClick()
            compose.onNodeWithText("Пункт").performTextInput("Первый")
            val screenshot = File(app.getExternalFilesDir(null), "checklists/editor-360dp.png")
            screenshot.parentFile!!.mkdirs()
            screenshot.outputStream().use {
                compose.onNodeWithTag("card-editor").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(10000) { model.state.value.cards.any { it.noteType == NoteType.CHECKLIST } }
            var note = model.state.value.cards.single { it.noteType == NoteType.CHECKLIST }
            assertEquals(listOf("Первый", "Последний"), note.checklistItems().map { it.text })
            assertEquals(0, note.progress)
            compose.onNodeWithText("Покупки").performClick()
            compose.onNodeWithTag("checklist-toggle-${note.checklistItems().first().id}").performScrollTo().performClick()
            compose.waitUntil(10000) { model.state.value.cards.first { it.id == note.id }.progress == 50 }
            compose.onNodeWithTag("checklist-counter").performScrollTo().assertTextEquals("Выполнено 1 из 2")
            compose.onNodeWithTag("details-status-${note.id}").performScrollTo().performClick()
            compose.onNodeWithText("Рассчитывается по выполненным пунктам").assertIsDisplayed()
            compose.onNodeWithTag("progress-input").assertDoesNotExist()
            compose.onNodeWithTag("percent-cancel").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            note = model.state.value.cards.first { it.id == note.id }
            compose.onNodeWithTag("checklist-text-${note.checklistItems().first().id}").performScrollTo().performTextReplacement("Несохранённый пункт")
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertEquals("Первый", db.cardDao().findById(note.id)!!.checklistItems().first().text)
            assertEquals(42, db.cardDao().findById(1)!!.progress)
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Новая обычная")
            compose.onNodeWithTag("checklist-add-last").assertDoesNotExist()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(10000) { model.state.value.cards.any { it.title == "Новая обычная" } }
            assertEquals(NoteType.NOTE, model.state.value.cards.single { it.title == "Новая обычная" }.noteType)
        } finally {
            if (modelCreated) model.viewModelScope.cancel()
            db.close(); app.deleteSharedPreferences(name); root.deleteRecursively()
        }
    }

    @Test fun handleDragChangesOrderWithoutTogglingAndScrollsAtEdge() {
        var items by mutableStateOf((0..29).map { ChecklistItem("$it", "Пункт $it") })
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent { OnTopTheme {
            scroll = rememberScrollState()
            var viewport by remember { mutableStateOf(Rect.Zero) }
            Column(Modifier.width(360.dp).height(420.dp).onGloballyPositioned { viewport = it.boundsInRoot() }.verticalScroll(scroll)) {
                ChecklistEditor(items, { items = it }, true, scroll, viewport)
            }
        } }
        val handle = compose.onNodeWithTag("checklist-drag-0").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(handle.center)
            moveTo(androidx.compose.ui.geometry.Offset(handle.center.x, 1050f), 500)
        }
        compose.waitUntil(5000) { scroll.value > 0 }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(items.indexOfFirst { it.id == "0" } > 0)
            assertTrue(items.none { it.done })
            assertEquals(30, items.size)
        }
        compose.onNodeWithTag("checklist-add-last").performScrollTo().performClick()
        compose.onNodeWithText("Пункт").assertIsFocused()
        assertEquals(30, Checklist.clean(items).size)
    }
}
