package com.ontop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class CardSizeSelectorTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp font={1}")
        fun sizes() = listOf(arrayOf<Any>(320, 1f), arrayOf<Any>(280, 2f))
    }

    @Test fun horizontalTilesHaveDistinctSelectionAndReadableLabels() {
        var size by mutableStateOf(CardSize.NORMAL)
        var enabled by mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OnTopTheme(darkTheme = false) {
                    Box(Modifier.width(width.dp).padding(horizontal = 16.dp)) {
                        CardSizeSelector(size, enabled) { size = it }
                    }
                }
            }
        }
        val normal = compose.onNodeWithTag("size-NORMAL")
        val wide = compose.onNodeWithTag("size-WIDE")
        normal.assertIsSelected()
        wide.assertIsNotSelected()
        compose.onNodeWithText("Занимает одну колонку доски.").assertIsDisplayed()
        val first = normal.fetchSemanticsNode().boundsInRoot
        val second = wide.fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.bottom, second.bottom, 1f)
        assertTrue(first.right < second.left)
        fun assertFill(tag: String, expected: Int) {
            // Android's RippleDrawable uses real time, independently of the Compose test clock.
            compose.waitUntil(5_000) {
                val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                val matches = expected == bitmap.getPixel(bitmap.width / 2, 12)
                bitmap.recycle()
                matches
            }
        }
        assertFill("size-NORMAL", android.graphics.Color.rgb(220, 229, 212))
        assertFill("size-WIDE", android.graphics.Color.rgb(245, 240, 233))
        wide.performClick()
        // Inspect the persistent selection after the press ripple has finished.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        wide.assertIsSelected()
        normal.assertIsNotSelected()
        compose.onNodeWithTag("size-check-WIDE", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("size-check-NORMAL", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Занимает всю ширину доски.").assertIsDisplayed()
        assertFill("size-WIDE", android.graphics.Color.rgb(220, 229, 212))
        assertFill("size-NORMAL", android.graphics.Color.rgb(245, 240, 233))
        for (name in listOf("NORMAL", "WIDE")) {
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("size-label-$name", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            assertFalse(result.single().hasVisualOverflow)
            assertEquals(1, result.single().lineCount)
        }
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if (output != null) {
            val file = File(output, "design-review/size-selector-${width}dp-${fontScale}x.png")
            file.parentFile!!.mkdirs()
            val bitmap = compose.onNodeWithTag("card-size-selector").captureToImage().asAndroidBitmap()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.runOnIdle { enabled = false }
        normal.assertIsNotEnabled()
        wide.assertIsNotEnabled()
    }
}
