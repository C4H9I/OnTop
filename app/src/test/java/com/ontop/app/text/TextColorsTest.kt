package com.ontop.app.text

import org.junit.Assert.*
import org.junit.Test

class TextColorsTest {
    @Test fun fixedPaletteCanBeAssignedIndependentlyWithoutLosingBoldItalicOrOtherColours() {
        val base = RichDocument("abcdefgh").toggle(RichKind.BOLD, 0, 8).toggle(RichKind.ITALIC, 0, 8)
        textColorPalette.forEach { choice ->
            val colored = base.withTextColor(1, 4, choice.value)
            assertEquals(TextColorSelection(choice.value), colored.selectedTextColor(1, 4))
            assertTrue(colored.has(RichKind.BOLD, 0, 8)); assertTrue(colored.has(RichKind.ITALIC, 0, 8))
            assertEquals(colored, colored.withTextColor(1, 4, choice.value))
            assertEquals(base, colored.withTextColor(1, 4, null))
        }
        val multiple = base.withTextColor(0, 3, "#477451").withTextColor(3, 8, "#416A9B")
        assertTrue(multiple.selectedTextColor(0, 8).mixed)
        assertEquals("#477451", multiple.selectedTextColor(3, 0).value)
        assertEquals("#416A9B", multiple.selectedTextColor(5, 5).value)
        assertTrue(multiple.withTextColor(0, 3, null).has(RichKind.COLOR, 3, 8, "#416A9B"))
    }

    @Test fun legacyAndCustomValuesSurviveNormalizationEditsAndSelection() {
        (RichDocument.COLOR_TOKENS + listOf("#123456", "#AA345678")).forEach { value ->
            val doc = RichDocument("note").withTextColor(0, 4, value)
            assertEquals(value, doc.normalized().ranges.single().color)
            assertEquals("Другой цвет", doc.selectedTextColor(0, 4).name)
            assertTrue(doc.replace(2, 2, "X").has(RichKind.COLOR, 0, 5, value))
        }
        val plain = RichDocument("note")
        assertEquals(plain, plain.withTextColor(2, 2, "#477451"))
        assertEquals(plain, plain.withTextColor(0, 4, "invalid"))
        assertTrue(plain.withTextColor(1, 3, "#477451").selectedTextColor(0, 4).mixed)
    }
}
