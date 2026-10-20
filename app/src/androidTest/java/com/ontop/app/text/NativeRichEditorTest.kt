package com.ontop.app.text

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.inputmethod.BaseInputConnection
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NativeRichEditorTest {
    @Test fun delayedParentValueDoesNotOverwriteNativeEditOrSelection() = onMain {
        val editor = NoteEditText(InstrumentationRegistry.getInstrumentation().targetContext)
        val controller = NativeRichController().apply { attach(editor) }
        val original = RichDocument("До")
        controller.update(original, false, 2, 2)
        controller.insert("\nПосле 🧠")
        val edited = controller.document
        controller.update(original, false, 0, 0)
        assertEquals(edited.text, editor.text.toString())
        assertEquals(edited.text.length, editor.selectionStart)
        controller.update(edited, false, 0, 0)
        controller.update(edited.copy(ranges = listOf(RichRange(0, 2, RichKind.BOLD))), false, 0, 0)
        assertEquals(edited.text.length, editor.selectionStart)
        controller.update(RichDocument("Внешнее обновление"), false, 3, 3)
        assertEquals("Внешнее обновление", editor.text.toString())
        assertEquals(3, editor.selectionStart)
    }
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { failure = runCatching(block).exceptionOrNull() }
        failure?.let { throw it }
    }
    @Test fun actualEditableShowsBothStylesPreservesSelectionAndTracksInsertDeleteAndImeComposition() = onMain {
        val editor = NoteEditText(InstrumentationRegistry.getInstrumentation().targetContext)
        val controller = NativeRichController()
        controller.attach(editor)
        controller.update(RichDocument("Важная мысль"), false, 0, 6)
        controller.format(RichKind.BOLD)
        controller.format(RichKind.ITALIC)
        assertEquals(0, editor.selectionStart)
        assertEquals(6, editor.selectionEnd)
        val spans = editor.editableText.getSpans(0, 6, StyleSpan::class.java)
        assertTrue(spans.any { it.style == Typeface.BOLD })
        assertTrue(spans.any { it.style == Typeface.ITALIC })
        controller.select(3, 3)
        editor.editableText.insert(3, "XYZ")
        assertEquals("ВажXYZная мысль", controller.document.text)
        assertTrue(controller.document.has(RichKind.BOLD, 0, 9))
        assertTrue(controller.document.has(RichKind.ITALIC, 0, 9))
        editor.editableText.delete(3, 6)
        assertEquals("Важная мысль", controller.document.text)
        val connection = BaseInputConnection(editor, true)
        connection.setComposingRegion(1, 4)
        val from = BaseInputConnection.getComposingSpanStart(editor.editableText)
        val to = BaseInputConnection.getComposingSpanEnd(editor.editableText)
        controller.select(0, 6)
        controller.format(RichKind.STRIKE)
        assertEquals(from, BaseInputConnection.getComposingSpanStart(editor.editableText))
        assertEquals(to, BaseInputConnection.getComposingSpanEnd(editor.editableText))
        controller.format(RichKind.BOLD)
        assertFalse(controller.document.has(RichKind.BOLD, 0, 6))
        assertTrue(controller.document.has(RichKind.ITALIC, 0, 6))
        assertEquals(0, editor.selectionStart)
        assertEquals(6, editor.selectionEnd)
    }
    @Test fun nativeBlockSpansRemainValidAfterNewlinesAndClipboardStylesDoNotBecomeUnsavedFormatting() = onMain {
        val editor = NoteEditText(InstrumentationRegistry.getInstrumentation().targetContext)
        val controller = NativeRichController()
        controller.attach(editor)
        controller.update(RichDocument("Один\nДва"), false, 0, 0)
        controller.format(RichKind.BULLET)
        editor.editableText.insert(2, "\n")
        assertEquals("Од\nин\nДва", controller.document.text)
        assertTrue(controller.document.has(RichKind.BULLET, 3, 3))
        controller.select(0, 2)
        controller.format(RichKind.HEADING1)
        assertFalse(controller.document.has(RichKind.BULLET, 0, 0))
        val foreign = android.text.SpannableString("paste").apply { setSpan(StyleSpan(Typeface.ITALIC), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        editor.editableText.append(foreign)
        assertEquals("Од\nин\nДваpaste", controller.document.text)
        assertFalse(controller.document.ranges.any { it.kind == RichKind.ITALIC })
        assertTrue(editor.editableText.getSpans(9, editor.length(), StyleSpan::class.java).none { it.style == Typeface.ITALIC })
    }
}
