package com.ontop.app.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

internal fun richColor(token: String?, dark: Boolean): Color = when (token) {
    "forest" -> if (dark) Color(0xFFB5CEA9) else Color(0xFF304734)
    "clay" -> if (dark) Color(0xFFF0B39A) else Color(0xFF8A4834)
    "moss" -> if (dark) Color(0xFFCCD39B) else Color(0xFF58652E)
    "earth" -> if (dark) Color(0xFFE4C6A5) else Color(0xFF72553C)
    else -> if (validTextColor(token) && token?.startsWith("#") == true) {
        val hex = token.drop(1).toLong(16)
        Color(if (token.length == 7) hex or 0xFF000000 else hex)
    } else Color.Unspecified
}

internal fun RichDocument.annotated(fontSize: TextUnit, dark: Boolean): AnnotatedString {
    val document = normalized()
    val bullets = document.ranges.filter { it.kind == RichKind.BULLET }.map { it.start }.toSet()
    val offsets = IntArray(text.length + 1)
    val ends = IntArray(text.length + 1)
    return buildAnnotatedString {
        for (index in 0..text.length) {
            ends[index] = length
            if (index in bullets) append("• ")
            offsets[index] = length
            if (index < text.length) append(text[index])
        }
        document.ranges.forEach { range ->
            val style = when (range.kind) {
                RichKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                RichKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                RichKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                RichKind.COLOR -> SpanStyle(color = richColor(range.color, dark))
                RichKind.HEADING1 -> SpanStyle(fontSize = fontSize * 1.45f, fontWeight = FontWeight.Bold)
                RichKind.HEADING2 -> SpanStyle(fontSize = fontSize * 1.2f, fontWeight = FontWeight.Bold)
                RichKind.BULLET -> SpanStyle()
            }
            if (range.start < range.end) {
                addStyle(style, offsets[range.start], ends[range.end])
                if (range.kind == RichKind.HEADING1 || range.kind == RichKind.HEADING2) {
                    addStyle(ParagraphStyle(lineHeight = fontSize * if (range.kind == RichKind.HEADING1) 1.8f else 1.55f),
                        offsets[range.start], ends[range.end])
                }
            }
        }
        Regex("https?://[^\\s<>]+").findAll(text).forEach { match ->
            val url = match.value.trimEnd('.', ',', ';', '!', '?', ')', ']')
            if (url.isNotEmpty()) addLink(androidx.compose.ui.text.LinkAnnotation.Url(url),
                offsets[match.range.first], ends[match.range.first + url.length])
        }
    }
}
