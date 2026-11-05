package com.ontop.app.drawing

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.backup.BackupManager
import com.ontop.app.text.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DrawingEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createToolsSaveReopenCancelAndVersionedEditInNote() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "drawing-ui-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        lateinit var model: BoardViewModel
        var initialized = false
        val paths = mutableSetOf<String>()
        try {
            instrumentation.runOnMainSync { model = BoardViewModel(app, db,
                BackupManager(app, db, root = root, refreshWidgets = {}), BoardPreferences(app, name)) }
            initialized = true
            withTimeout(5000) { model.state.first { !it.loading } }
            compose.setContent { OnTopTheme { Box(Modifier.width(360.dp).fillMaxHeight()) {
                BoardScreen(model, LocalLifecycleOwner.current.lifecycle, null, {})
            } } }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Мой рисунок")
            compose.onNodeWithText("Нарисовать").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("drawing-canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("drawing-undo").assertIsNotEnabled()
            val pencil = compose.onNodeWithTag("drawing-pencil-tool")
            pencil.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Выбран"))
            val density = app.resources.displayMetrics.density
            assertTrue(pencil.fetchSemanticsNode().boundsInRoot.width >= 48f * density - 1f)
            fun line() { compose.onNodeWithTag("drawing-canvas").performTouchInput {
                down(Offset(width * .2f, height * .4f)); moveTo(Offset(width * .8f, height * .4f), 400); up()
            } }
            compose.onNodeWithTag("drawing-color-Синий").performClick()
            compose.onNodeWithTag("drawing-width").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(18f) }
            line()
            compose.onNodeWithTag("drawing-eraser-tool").performClick()
            compose.onNodeWithTag("drawing-eraser-tool").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Выбран"))
            compose.onNodeWithTag("drawing-color-Синий").assertDoesNotExist()
            compose.onNodeWithTag("drawing-canvas").performTouchInput {
                down(Offset(width * .5f, height * .2f)); moveTo(Offset(width * .5f, height * .8f), 400); up()
            }
            compose.onNodeWithTag("drawing-undo").performClick()
            compose.onNodeWithTag("drawing-redo").performClick()
            compose.onNodeWithTag("drawing-clear-tool").performClick()
            compose.onAllNodesWithText("Очистить").filter(hasAnyAncestor(isDialog())).onLast().performClick()
            compose.onNodeWithTag("drawing-undo").performClick()
            compose.onNodeWithTag("drawing-pencil-tool").performClick(); line()
            compose.onNodeWithTag("drawing-redo").assertIsNotEnabled()
            compose.onNodeWithTag("drawing-save").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Редактировать рисунок").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(10000) { model.state.value.cards.any { it.title == "Мой рисунок" } }
            var card = db.cardDao().allCards().single()
            var path = RichTextCodec.decode(card.description, card.formatting).media.single().path!!; paths += path
            val original = CardImageStore(app).resolve(path).readBytes()
            val originalModel = DrawingStore.load(app, path)
            assertTrue(originalModel.operations.any { it.type == "ERASE" }); assertEquals(18f, originalModel.operations.first().width)
            compose.onNodeWithText("Мой рисунок").performClick()
            compose.onNodeWithContentDescription("Редактировать").performClick()
            compose.onNodeWithText("Редактировать рисунок").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("drawing-canvas").fetchSemanticsNodes().isNotEmpty() }
            line()
            compose.onNodeWithTag("drawing-back").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertArrayEquals(original, CardImageStore(app).resolve(path).readBytes())
            compose.onNodeWithText("Редактировать рисунок").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("drawing-canvas").fetchSemanticsNodes().isNotEmpty() }
            line(); compose.onNodeWithTag("drawing-save").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("drawing-editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(10000) { model.state.value.cards.any { RichTextCodec.decode(it.description, it.formatting).media.singleOrNull()?.path != path } }
            card = db.cardDao().allCards().single()
            val updatedPath = RichTextCodec.decode(card.description, card.formatting).media.single().path!!; paths += updatedPath
            assertNotEquals(path, updatedPath)
            assertEquals(originalModel.operations.size + 1, DrawingStore.load(app, updatedPath).operations.size)
            assertNull(card.imagePath)
        } finally {
            if (initialized) model.viewModelScope.cancel()
            db.close(); paths.forEach { CardImageStore(app).delete(it) }
            app.deleteSharedPreferences(name); root.deleteRecursively()
        }
    }
}
