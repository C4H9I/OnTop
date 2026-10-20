package com.ontop.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotePercentagesUiTest {
    @get:Rule val compose = createComposeRule()
    private fun range(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun set(tag: String, value: Float) = compose.onNodeWithTag(tag).performScrollTo()
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun field(tag: String, text: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)

    @Test fun inputsDialSliderTrackingSaveCancelAndStateRestorationStayInSync() {
        var importance by mutableIntStateOf(50)
        var progress by mutableStateOf<Int?>(null)
        var open by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { OnTopTheme { Surface {
            Button(onClick = { open = true }) { Text("Открыть проценты") }
            if (open) NotePercentagesDialog(1, importance, progress, { open = false }, { i, p ->
                importance = i; progress = p; open = false
            })
        } } }
        compose.onNodeWithTag("progress-tracking").assertIsOff()
        compose.onNodeWithTag("progress-input").assertIsNotEnabled()
        for (invalid in listOf("", "101", "-1", "abc", "1.5")) {
            field("importance-input", invalid)
            compose.onNodeWithTag("percent-save").assertIsNotEnabled()
        }
        field("importance-input", "1")
        assertEquals(1f, range("importance-slider"), .01f)
        set("importance-slider", 50f)
        compose.onNodeWithTag("importance-input").assertTextContains("50")
        compose.onNodeWithTag("importance-dial").performScrollTo().performTouchInput {
            val radius = width * .4f
            down(center + Offset(0f, -radius))
            moveTo(center + Offset(radius, 0f), 200)
            up()
        }
        compose.onNodeWithTag("importance-input").assertTextContains("25")
        assertEquals(25f, range("importance-slider"), 1f)
        compose.onNodeWithTag("progress-tracking").performScrollTo().performClick()
        field("progress-input", "100")
        assertEquals(100f, range("progress-dial"), .01f)
        set("progress-slider", 50f)
        compose.onNodeWithTag("progress-input").assertTextContains("50")
        compose.onNodeWithTag("progress-dial").performScrollTo().performTouchInput {
            val radius = width * .4f
            down(center + Offset(0f, -radius)); moveTo(center + Offset(radius, 0f), 200); up()
        }
        compose.onNodeWithTag("progress-input").assertTextContains("25")
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("importance-input").assertTextContains("25")
        compose.onNodeWithTag("progress-tracking").assertIsOn()
        compose.onNodeWithTag("progress-input").assertTextContains("25")
        compose.onNodeWithTag("percent-cancel").performClick()
        compose.runOnIdle { assertEquals(50, importance); assertNull(progress) }
        compose.onNodeWithText("Открыть проценты").performClick()
        field("importance-input", "100")
        compose.onNodeWithTag("progress-tracking").performScrollTo().performClick()
        field("progress-input", "0")
        compose.onNodeWithTag("percent-save").performClick()
        compose.runOnIdle { assertEquals(100, importance); assertEquals(0, progress) }
        compose.onNodeWithText("Открыть проценты").performClick()
        compose.onNodeWithTag("progress-tracking").assertIsOn().performScrollTo().performClick()
        compose.onNodeWithTag("percent-save").performClick()
        compose.runOnIdle { assertNull(progress) }
    }

    @Test fun lightAndDarkRenderBothSizesAndBoundaryValues() {
        var dark by mutableStateOf(false)
        val pairs = listOf(0 to null, 1 to 0, 50 to 50, 99 to 100, 100 to null)
        compose.setContent { OnTopTheme(darkTheme = dark) { Surface {
            Column(Modifier.padding(8.dp)) {
                pairs.forEach { (importance, progress) -> Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    NoteStatusIndicator(importance, progress, diameter = 100.dp)
                    NoteStatusIndicator(importance, progress, variant = StatusIndicatorSize.Compact)
                } }
            }
        } } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { dark = theme == "dark" }
            pairs.forEach { (importance, progress) ->
                compose.onAllNodesWithContentDescription("Важность: $importance%. Готовность: ${progress?.let { "$it%" } ?: "не отслеживается"}")
                    .assertCountEquals(2)
            }
            val directory = File(context.getExternalFilesDir(null), "status-indicators").apply { mkdirs() }
            File(directory, "$theme.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
