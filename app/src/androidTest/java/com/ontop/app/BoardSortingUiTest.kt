package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class BoardSortingUiTest(private val width: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "portrait-{0}dp")
        fun widths() = listOf(arrayOf(360), arrayOf(393))
    }
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureBoardSorting") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "board-sorting").apply { mkdirs() }
        compose.waitForIdle()
        // Popup coordinates are relative to their own window; capture the display, including it.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Unable to capture sorting UI")
        try { File(directory, "$name-$width.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    @Test fun menuSortsVisibleTilesAndRestoresManualOrderOnPhoneWidth() {
        val cards = listOf(
            CardEntity(1, "Первая", size = CardSize.WIDE, createdAt = 1, updatedAt = 3, sortOrder = 0, importance = 0),
            CardEntity(2, "Вторая", size = CardSize.WIDE, createdAt = 2, updatedAt = 2, sortOrder = 1, importance = 100))
        var state by mutableStateOf(BoardState(cards = cards, loading = false))
        compose.setContent { OnTopTheme {
            Box(Modifier.width(width.dp).fillMaxHeight().testTag("sorting-root")) {
                BoardContent(state, {}, {}, onSort = { state = state.copy(sortMode = it) })
            }
        } }
        fun assertFirst(id: Long) {
            val first = compose.onNodeWithTag("card-$id").fetchSemanticsNode().boundsInRoot
            val other = compose.onNodeWithTag("card-${if (id == 1L) 2 else 1}").fetchSemanticsNode().boundsInRoot
            assertTrue(first.top < other.top)
        }
        assertFirst(1)
        for (mode in listOf(BoardSort.IMPORTANCE, BoardSort.CREATED, BoardSort.UPDATED, BoardSort.MANUAL)) {
            compose.onNodeWithTag("board-sort").performClick()
            compose.onNodeWithTag("board-sort-${state.sortMode.name}").assertIsSelected()
            BoardSort.entries.forEach { compose.onNodeWithTag("board-sort-${it.name}").assertIsDisplayed() }
            if (mode == BoardSort.IMPORTANCE) capture("menu")
            compose.onNodeWithTag("board-sort-${mode.name}").performClick()
            assertFirst(if (mode in listOf(BoardSort.IMPORTANCE, BoardSort.CREATED)) 2 else 1)
        }
        compose.onNodeWithContentDescription("Сортировка доски: Мой порядок").assertExists()
        val root = compose.onNodeWithTag("sorting-root").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("board-sort").fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= root.left && button.right <= root.right)
        compose.onNodeWithContentDescription("Меню приложения").assertIsDisplayed()
        capture("board")
        compose.runOnIdle { state = state.copy(sortMode = BoardSort.IMPORTANCE,
            cards = cards.map { if (it.id == 1L) it.copy(importance = 100) else it }) }
        assertFirst(1)
        compose.runOnIdle { state = state.copy(cards = state.cards.filter { it.id != 1L }) }
        compose.onNodeWithTag("card-1").assertDoesNotExist()
        compose.onNodeWithTag("card-2").assertIsDisplayed()
    }

    @Test fun importanceHasFivePercentStepsAndSaveReopenDiscardKeepOriginalValue() {
        var card by mutableStateOf(CardEntity(1, "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0))
        var open by mutableStateOf(true)
        var session by mutableIntStateOf(0)
        compose.setContent { OnTopTheme {
            Box(Modifier.width(width.dp).fillMaxHeight()) {
                if (open) key(session) {
                    CardEditorScreen(card, false, null, null, {}, {}, {}, { open = false }, { _, _, _ -> }, {},
                        onSaveWithImportance = { _, _, _, _, value -> card = card.copy(importance = value); open = false })
                } else Button(onClick = { session++; open = true }) { Text("Открыть") }
            }
        } }
        fun settings() {
            if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("editor-settings-compact").performClick()
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Другие настройки").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Другие настройки").performClick()
        }
        fun value(value: Float) {
            compose.onNodeWithTag("editor-importance").performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        settings()
        compose.onNodeWithText("Важность: 50%").assertIsDisplayed()
        value(0f); compose.onNodeWithText("Важность: 0%").assertIsDisplayed()
        value(100f); compose.onNodeWithText("Важность: 100%").assertIsDisplayed()
        value(57f); compose.onNodeWithText("Важность: 55%").assertIsDisplayed()
        capture("importance")
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("editor-save").performClick()
        assertEquals(55, card.importance)
        compose.onNodeWithText("Открыть").performClick()
        settings()
        compose.onNodeWithText("Важность: 55%").assertIsDisplayed()
        value(100f)
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        compose.onNodeWithText("Не сохранять").performClick()
        assertEquals(55, card.importance)
        compose.onNodeWithText("Открыть").performClick()
        settings()
        compose.onNodeWithText("Важность: 55%").assertIsDisplayed()
    }
}
