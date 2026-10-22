package com.ontop.app.gallery

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GallerySheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun expandAndCollapseWithGesturesThenDismiss() {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            OnTopTheme {
                if (open) GallerySheet(GalleryUiState(loading = false), remember { GalleryThumbnails(context) },
                    { open = false }, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("gallery-sheet").assertIsDisplayed()
        val initial = compose.onNodeWithTag("gallery-sheet").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("gallery-handle").performTouchInput { swipeUp(durationMillis = 500) }
        compose.waitForIdle()
        val full = compose.onNodeWithTag("gallery-sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Expanded sheet should occupy nearly all the screen", full > initial * 1.3f)
        compose.onNodeWithTag("gallery-handle").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        assertEquals(initial, compose.onNodeWithTag("gallery-sheet").fetchSemanticsNode().boundsInRoot.height, 8f)
        compose.onNodeWithTag("gallery-handle").performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("gallery-sheet").assertDoesNotExist()
    }

    @Test fun limitedAccessCameraSystemPickerFileTabAndMediaSelection() {
        var requests = 0; var camera = 0; var files = 0; var picker = 0
        var selected: GalleryItem? = null
        val photo = GalleryItem(Uri.parse("content://media/external/images/media/1"), 1, 2, false, name = "Photo")
        val video = GalleryItem(Uri.parse("content://media/external/video/media/2"), 2, 1, true, 65000, "Video")
        compose.setContent { OnTopTheme {
            GallerySheet(GalleryUiState(GalleryAccess(false, false, true), listOf(photo, video), loading = false, hasMore = false),
                remember { GalleryThumbnails(context) }, {}, { selected = it }, { camera++ }, { files++ }, { picker++ }, { requests++ }, {}, {})
        } }
        compose.onNodeWithText("Доступна только часть медиатеки").assertIsDisplayed()
        compose.onNodeWithTag("gallery-permission").performClick()
        compose.onNodeWithTag("gallery-system-picker").performClick()
        compose.onNodeWithTag("gallery-camera").performClick()
        compose.onNodeWithTag("gallery-file-tab").performClick()
        compose.onNodeWithTag("gallery-item-1").performClick()
        compose.runOnIdle { assertEquals(photo, selected) }
        compose.onNodeWithTag("gallery-item-2").performClick()
        compose.runOnIdle {
            assertEquals(video, selected)
            assertEquals(1, requests); assertEquals(1, camera); assertEquals(1, files); assertEquals(1, picker)
        }
        compose.onNodeWithText("▶ 1:05").assertIsDisplayed()
    }
}
