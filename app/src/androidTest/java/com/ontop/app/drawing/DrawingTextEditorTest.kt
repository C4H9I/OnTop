package com.ontop.app.drawing

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardImageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DrawingTextEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textCreateMoveStyleDeleteUndoSaveReopenAndCancel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var key by mutableStateOf(UUID.randomUUID().toString())
        var source by mutableStateOf<String?>(null)
        var visible by mutableStateOf(true)
        var saved: String? = null
        try {
            compose.setContent {
                if (visible) DrawingEditor(key, source, { visible = false }, { saved = it; visible = false })
            }
            fun ready() = compose.waitUntil(5000) { compose.onAllNodesWithTag("drawing-canvas").fetchSemanticsNodes().isNotEmpty() }
            ready()
            compose.onNodeWithTag("drawing-viewport").performTouchInput {
                down(0, center - Offset(80f, 0f)); down(1, center + Offset(80f, 0f))
                updatePointerTo(0, center - Offset(200f, 0f)); updatePointerTo(1, center + Offset(200f, 0f)); move(200)
                up(0); up(1)
            }
            compose.onNodeWithTag("drawing-zoom").assertTextEquals("250%")
            compose.onNodeWithTag("drawing-undo").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Вписать в экран").performClick()
            compose.onNodeWithTag("drawing-zoom").assertTextEquals("100%")
            compose.onNodeWithTag("drawing-text-tool").performClick()
            compose.onNodeWithTag("drawing-canvas").performTouchInput { click(Offset(width * .2f, height * .2f)) }
            compose.onNodeWithTag("drawing-text-input").performTextInput("Привет\nOnTop 123")
            compose.onNodeWithText("Добавить / сохранить").performClick()
            compose.onNodeWithTag("drawing-color-Синий").performClick()
            compose.onNodeWithTag("drawing-text-size").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
            compose.onNodeWithTag("drawing-canvas").performTouchInput {
                down(Offset(width * .22f, height * .22f)); moveTo(Offset(width * .45f, height * .45f), 300); up()
            }
            compose.onNodeWithText("Изменить").performClick()
            compose.onNodeWithTag("drawing-text-input").performTextReplacement("Надпись\nOnTop 123")
            compose.onNodeWithText("Добавить / сохранить").performClick()
            compose.onNodeWithText("Удалить").performClick()
            compose.onNodeWithTag("drawing-undo").performClick()
            compose.onNodeWithTag("drawing-redo").performClick()
            compose.onNodeWithTag("drawing-undo").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.waitUntil(10000) { saved != null }
            val path = saved!!
            val document = DrawingStore.load(context, path)
            val text = document.texts.single()
            assertEquals("Надпись\nOnTop 123", text.text)
            assertEquals(40f, text.fontSize)
            assertEquals(0xFF1565C0.toInt(), text.color)
            assertTrue(text.x > 120f); assertTrue(text.y > 160f)
            assertTrue(document.operations.isEmpty())
            val bytes = CardImageStore(context).resolve(path).readBytes()
            compose.runOnIdle { source = path; key = UUID.randomUUID().toString(); visible = true }
            ready()
            compose.onNodeWithTag("drawing-text-tool").performClick()
            compose.onNodeWithTag("drawing-canvas").performTouchInput {
                click(Offset((text.x + 5) / 600 * width, (text.y + 5) / 800 * height))
            }
            compose.onNodeWithText("Изменить").performClick()
            compose.onNodeWithTag("drawing-text-input").performTextReplacement("Отменить")
            compose.onNodeWithText("Добавить / сохранить").performClick()
            compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertEquals(document, DrawingStore.load(context, path))
            assertArrayEquals(bytes, CardImageStore(context).resolve(path).readBytes())
        } finally { saved?.let { CardImageStore(context).delete(it) } }
    }
}
