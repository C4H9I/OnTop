package com.ontop.app.text

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.*
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private interface OwnedSpan
private class BoldSpan : StyleSpan(Typeface.BOLD), OwnedSpan
private class ItalicSpan : StyleSpan(Typeface.ITALIC), OwnedSpan
private class StrikeSpan : StrikethroughSpan(), OwnedSpan
private class ColorSpan(color: Int) : ForegroundColorSpan(color), OwnedSpan
private class ListSpan(gap: Int, color: Int) : BulletSpan(gap, color), OwnedSpan
private class HeadingSpan(private val scale: Float) : MetricAffectingSpan(), OwnedSpan {
    private fun apply(paint: TextPaint) {
        paint.textSize *= scale
        paint.typeface = Typeface.create(paint.typeface, (paint.typeface?.style ?: 0) or Typeface.BOLD)
    }
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
}

internal class NoteEditText(context: Context) : EditText(context) {
    var selectionObserver: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (start >= 0 && end >= 0) selectionObserver?.invoke(start, end)
    }
}

/** Android owns the input connection, composition, selection handles and native edit coordinates. */
internal class NativeRichController {
    var view: NoteEditText? = null
        private set
    var document = RichDocument()
        private set
    var start by mutableIntStateOf(0)
        private set
    var end by mutableIntStateOf(0)
        private set
    var onDocument: (RichDocument) -> Unit = {}
    var onSelection: (Int, Int) -> Unit = { _, _ -> }
    var onFocus: (Boolean) -> Unit = {}
    var onYouTubePaste: (String, Int, Int) -> Unit = { _, _, _ -> }
    private var internalChange = false
    private var dark = false

    fun attach(editor: NoteEditText) {
        view = editor
        editor.selectionObserver = { s, e -> start = s; end = e; onSelection(s, e) }
        editor.setOnFocusChangeListener { _, focused -> onFocus(focused) }
        editor.addTextChangedListener(object : TextWatcher {
            private var previous = RichDocument()
            private var from = 0
            private var removed = 0
            private var added = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!internalChange) { previous = document; from = start; removed = count; added = after }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (internalChange || s == null) return
                // Never replace Editable here: IME composition spans and the cursor stay intact.
                val inserted = s.subSequence(from, from + added).toString()
                document = previous.replace(from, from + removed, inserted)
                applyStyles()
                onDocument(document)
                if (added > 1 && YouTubeLink.parse(inserted) != null) onYouTubePaste(inserted, from, from + added)
            }
        })
    }

    fun update(value: RichDocument, darkTheme: Boolean, selectionStart: Int, selectionEnd: Int) {
        val editor = view ?: return
        val newTheme = dark != darkTheme
        dark = darkTheme
        if (document != value || editor.text.toString() != value.text) {
            internalChange = true
            document = value.normalized()
            if (editor.text.toString() != value.text) editor.setText(value.text)
            applyStyles()
            editor.setSelection(selectionStart.coerceIn(0, value.text.length), selectionEnd.coerceIn(0, value.text.length))
            internalChange = false
        } else if (newTheme) applyStyles()
    }

    private fun applyStyles() {
        val editor = view ?: return
        val text = editor.editableText
        text.getSpans(0, text.length, OwnedSpan::class.java).forEach(text::removeSpan)
        // Paste is intentionally plain text. Do not display foreign HTML/clipboard styles
        // that the structured document cannot save; preserve IME and spelling spans.
        text.getSpans(0, text.length, CharacterStyle::class.java).filter {
            it !is OwnedSpan && it !is SuggestionSpan && text.getSpanFlags(it) and Spannable.SPAN_COMPOSING == 0
        }.forEach(text::removeSpan)
        text.getSpans(0, text.length, ParagraphStyle::class.java).filter { it !is OwnedSpan }.forEach(text::removeSpan)
        for (range in document.ranges) {
            val span: Any = when (range.kind) {
                RichKind.BOLD -> BoldSpan()
                RichKind.ITALIC -> ItalicSpan()
                RichKind.STRIKE -> StrikeSpan()
                RichKind.COLOR -> ColorSpan(richColor(range.color, dark).toArgb())
                RichKind.HEADING1 -> HeadingSpan(1.45f)
                RichKind.HEADING2 -> HeadingSpan(1.2f)
                RichKind.BULLET -> ListSpan((8 * editor.resources.displayMetrics.density).toInt(), editor.currentTextColor)
            }
            text.setSpan(span, range.start, range.end,
                if (range.kind == RichKind.BULLET) Spannable.SPAN_PARAGRAPH else Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
        }
    }

    fun select(from: Int, to: Int) {
        val length = document.text.length
        view?.setSelection(from.coerceIn(0, length), to.coerceIn(0, length))
    }
    fun focus() {
        view?.let { editor ->
            editor.requestFocus()
            editor.post { ViewCompat.getWindowInsetsController(editor)?.show(WindowInsetsCompat.Type.ime()) }
        }
    }
    fun insert(text: String) {
        val from = minOf(start, end)
        view?.editableText?.replace(from, maxOf(start, end), text)
        select(from + text.length, from + text.length)
    }
    fun replaceAll(text: String) {
        view?.editableText?.replace(0, document.text.length, text)
        select(text.length, text.length)
    }
    fun format(kind: RichKind, color: String? = null) {
        document = document.toggle(kind, start, end, color)
        applyStyles()
        onDocument(document)
    }
    fun clear() {
        document = document.clear(start, end)
        applyStyles()
        onDocument(document)
    }
    fun clearColor() {
        document = document.without(RichKind.COLOR, start, end)
        applyStyles()
        onDocument(document)
    }
    fun setColor(color: String?) {
        document = document.withTextColor(start, end, color)
        applyStyles()
        onDocument(document)
    }
    fun plainParagraph() {
        val paragraphs = document.selectedParagraphs(start, end)
        document = document.copy(ranges = document.ranges.filterNot { range ->
            range.kind.block && paragraphs.any { it.first == range.start }
        }).normalized()
        applyStyles()
        onDocument(document)
    }
}

@Composable
internal fun NativeRichEditor(document: RichDocument, onChange: (RichDocument) -> Unit, enabled: Boolean,
    controller: NativeRichController, onFocused: (Boolean) -> Unit, modifier: Modifier = Modifier,
    minimumLines: Int = 12, editorTag: String = "editor-description", placeholder: String = "О чём хочется помнить?") {
    var selectionStart by rememberSaveable { mutableIntStateOf(0) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onChange)
    val focusCallback by rememberUpdatedState(onFocused)
    controller.onDocument = { callback(it) }
    controller.onFocus = { focused = it; focusCallback(it) }
    controller.onSelection = { start, end -> selectionStart = start; selectionEnd = end }
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val style = MaterialTheme.typography.bodyLarge
    val fontPixels = with(LocalDensity.current) { style.fontSize.toPx() }
    AndroidView(factory = { context ->
        NoteEditText(context).apply {
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            contentDescription = "Содержимое заметки"
            isSaveEnabled = false // The plain document/ranges/selection use Compose SavedState.
            controller.attach(this)
        }
    }, update = { editor ->
        // A first media insertion splits the original, already mounted 12-line field.
        // Apply changing layout parameters here too, not only in the AndroidView factory.
        if (editor.minLines != minimumLines) editor.minLines = minimumLines
        if (editor.hint?.toString() != placeholder) editor.hint = placeholder
        editor.isEnabled = enabled
        editor.setTextColor(colors.onBackground.toArgb())
        editor.setHintTextColor(colors.onSurfaceVariant.copy(alpha = 0.65f).toArgb())
        editor.highlightColor = colors.primary.copy(alpha = 0.22f).toArgb()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            editor.textCursorDrawable?.setTint(colors.primary.toArgb())
            editor.textSelectHandle?.setTint(colors.primary.toArgb())
            editor.textSelectHandleLeft?.setTint(colors.primary.toArgb())
            editor.textSelectHandleRight?.setTint(colors.primary.toArgb())
        }
        editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPixels)
        controller.update(document, dark, selectionStart, selectionEnd)
    }, modifier = modifier.fillMaxWidth().testTag(editorTag).semantics {
        editableText = AnnotatedString(document.text)
        textSelectionRange = TextRange(selectionStart, selectionEnd)
        this.focused = focused
        if (!enabled) disabled()
        requestFocus { controller.focus(); true }
        setText { if (enabled) { controller.replaceAll(it.text); true } else false }
        insertTextAtCursor { if (enabled) { controller.insert(it.text); true } else false }
        setSelection { start, end, _ -> controller.select(start, end); true }
    })
}
