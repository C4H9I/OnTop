package com.ontop.app.text

import android.text.style.ForegroundColorSpan
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class TextColorPaletteTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf(false), arrayOf(true))
    }
    @get:Rule val compose = createComposeRule()

    @Test fun allSwatchesPreviewSelectionLegacyColoursAndRoomReopen() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "palette-${System.nanoTime()}.db"
        var document by mutableStateOf(RichDocument("Цвет и смысл").toggle(RichKind.BOLD, 0, 4)
            .toggle(RichKind.ITALIC, 0, 4).withTextColor(0, 4, "forest"))
        val controller = NativeRichController()
        var defaultColor = 0
        var db: OnTopDatabase? = null
        try {
            compose.setContent {
                OnTopTheme(darkTheme = dark) {
                    defaultColor = MaterialTheme.colorScheme.onSurface.toArgb()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding()) {
                        NativeRichEditor(document, { document = it }, true, controller, {})
                        RichFormatToolbar(document, controller, true)
                    }
                    }
                }
            }
            val field = compose.onNodeWithTag("editor-description")
            field.performTextInputSelection(TextRange(0, 4))
            compose.onNodeWithContentDescription("Цвет текста").performScrollTo().performClick()
            compose.onNodeWithTag("text-color-name").assertTextEquals("Другой цвет")
            for (choice in textColorPalette) {
                val swatch = compose.onNodeWithContentDescription(choice.name)
                swatch.performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("text-color-name").assertTextEquals(choice.name)
                val expected = choice.value?.let { richColor(it, dark).toArgb() } ?: defaultColor
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("text-color-preview").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(expected, layouts.single().layoutInput.style.color.toArgb())
                compose.runOnIdle {
                    assertEquals(0, controller.view!!.selectionStart)
                    assertEquals(4, controller.view!!.selectionEnd)
                    assertEquals(choice.value, document.selectedTextColor(0, 4).value)
                    assertTrue(document.has(RichKind.BOLD, 0, 4)); assertTrue(document.has(RichKind.ITALIC, 0, 4))
                    val spans = controller.view!!.editableText.getSpans(0, 4, ForegroundColorSpan::class.java)
                    if (choice.value == null) assertTrue(spans.isEmpty())
                    else assertEquals(expected, spans.single().foregroundColor)
                }
            }
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                val file = File(output, "design-review/text-palette-${if (dark) "dark" else "light"}.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithContentDescription("Синий").performScrollTo().performClick()
            compose.onNodeWithText("Обычный цвет").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(document.ranges.any { it.kind == RichKind.COLOR }) }
            compose.onNodeWithContentDescription("Синий").performScrollTo().performClick()
            compose.runOnIdle { controller.select(5, 7) }
            compose.onNodeWithContentDescription("Красный").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(document.has(RichKind.COLOR, 0, 4, "#416A9B"))
                assertTrue(document.has(RichKind.COLOR, 5, 7, "#B84747"))
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            val id = db.cardDao().insert(CardEntity(title = "Палитра", description = document.text,
                formatting = RichTextCodec.encode(document), createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            val saved = db.cardDao().findById(id)!!
            assertEquals(document, RichTextCodec.decode(saved.description, saved.formatting))
            (RichDocument.COLOR_TOKENS + "#123456").forEach { value ->
                val legacy = document.withTextColor(0, 4, value)
                assertEquals(legacy, RichTextCodec.decode(legacy.text, RichTextCodec.encode(legacy)))
            }
            compose.runOnIdle { controller.select(2, 2) }
            compose.onNodeWithText("Выделите текст, чтобы изменить его цвет").assertIsDisplayed()
            compose.onNodeWithContentDescription("Зелёный").assertIsNotEnabled()
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
