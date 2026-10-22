package com.ontop.app.text

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AttachmentUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun compactBlockRequiresConfirmationBeforeRemoval() {
        var removed = false
        compose.setContent {
            OnTopTheme {
                AttachmentView(NoteMedia(id = "file", offset = 0, kind = MediaKind.FILE,
                    path = "attachments/abc.bin", name = "Document.pdf", mime = "application/pdf", bytes = 1234),
                    Modifier, { removed = true }, true)
            }
        }
        compose.onNodeWithText("Document.pdf").assertIsDisplayed()
        compose.onNodeWithTag("remove-media-file").performClick()
        compose.onNodeWithText("Удалить вложение?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertFalse(removed) }
        compose.onNodeWithTag("remove-media-file").performClick()
        compose.onAllNodesWithText("Удалить").onLast().performClick()
        compose.runOnIdle { assertTrue(removed) }
    }
}
