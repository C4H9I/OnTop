package com.ontop.app.widget

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.ontop.app.CardEditorScreen
import com.ontop.app.data.CardEntity
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetRotationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modeChoicesRemainIndependentAndDisabledDuringSave() {
        var selected = WidgetMode.FIXED
        var enabled by mutableStateOf(true)
        compose.setContent { OnTopTheme {
            var mode by remember { mutableStateOf(WidgetMode.FIXED) }
            WidgetModeChoices(mode, enabled) { mode = it; selected = it }
        } }
        compose.onNodeWithText("Одна заметка").assertIsSelected()
        compose.onNodeWithText("Случайная ротация").performClick().assertIsSelected()
        assertEquals(WidgetMode.ROTATION, selected)
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("Одна заметка").assertIsNotEnabled()
        assertEquals(WidgetMode.ROTATION, selected)
    }

    @Test fun importanceSliderShowsValueSavesAndMakesDraftDirty() {
        var saved: Int? = null
        val card = CardEntity(id = 1, title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0)
        compose.setContent { OnTopTheme {
            CardEditorScreen(card, false, null, null, {}, {}, {}, {}, { _, _, _ -> }, {},
                onSaveWithImportance = { _, _, _, _, importance -> saved = importance })
        } }
        if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("editor-settings-compact").performClick()
        }
        compose.onNodeWithText("Другие настройки").performClick()
        compose.onNodeWithText("Важность: 50%").assertIsDisplayed()
        compose.onNodeWithTag("editor-importance").performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.onNodeWithText("Важность: 100%").assertIsDisplayed()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("editor-save").performClick()
        assertEquals(100, saved)
    }
}
