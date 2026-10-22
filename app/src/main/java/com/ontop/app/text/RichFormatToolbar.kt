package com.ontop.app.text

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.ontop.app.R
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun RichFormatToolbar(document: RichDocument, controller: NativeRichController, enabled: Boolean,
    onInsertImage: (() -> Unit)? = null, onInsertVideo: (() -> Unit)? = null, onInsertDrawing: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null, onInsertAudio: (() -> Unit)? = null) {
    val start = controller.start
    val end = controller.end
    val selection = start != end
    var headings by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)
        .testTag("format-toolbar"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        @Composable fun Action(label: String, symbol: String, kind: RichKind, style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current) {
            val active = document.has(kind, start, end)
            TextButton(onClick = { controller.format(kind) }, enabled = enabled && (kind.block || selection),
                modifier = Modifier.widthIn(min = 48.dp).focusProperties { canFocus = false }
                    .semantics { contentDescription = label; selected = active },
                colors = ButtonDefaults.textButtonColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                Text(symbol, style = style)
            }
        }
        IconButton(onClick = controller::copySelection, enabled = enabled && selection,
            modifier = Modifier.focusProperties { canFocus = false }) {
            Icon(painterResource(R.drawable.ic_copy), "Копировать")
        }
        IconButton(onClick = controller::pastePlainText, enabled = enabled,
            modifier = Modifier.focusProperties { canFocus = false }) {
            Icon(painterResource(R.drawable.ic_paste), "Вставить")
        }
        Action("Жирный", "B", RichKind.BOLD, LocalTextStyle.current.copy(fontWeight = FontWeight.Bold))
        Action("Курсив", "I", RichKind.ITALIC, LocalTextStyle.current.copy(fontStyle = FontStyle.Italic))
        Action("Зачёркнутый", "S", RichKind.STRIKE, LocalTextStyle.current.copy(textDecoration = TextDecoration.LineThrough))
        Box {
            TextButton(onClick = { headings = true }, enabled = enabled,
                modifier = Modifier.focusProperties { canFocus = false }.semantics { contentDescription = "Стиль абзаца" }) { Text("H") }
            DropdownMenu(expanded = headings, onDismissRequest = { headings = false }, containerColor = MaterialTheme.colorScheme.surface,
                properties = PopupProperties(focusable = false)) {
                DropdownMenuItem(text = { Text("Заголовок 1") }, onClick = { controller.format(RichKind.HEADING1); headings = false })
                DropdownMenuItem(text = { Text("Заголовок 2") }, onClick = { controller.format(RichKind.HEADING2); headings = false })
                DropdownMenuItem(text = { Text("Обычный текст") }, onClick = { controller.plainParagraph(); headings = false })
            }
        }
        Action("Маркированный список", "•", RichKind.BULLET)
        Box {
            TextButton(onClick = { colors = true }, enabled = enabled,
                modifier = Modifier.focusProperties { canFocus = false }.semantics { contentDescription = "Цвет текста" }) { Text("A") }
            DropdownMenu(expanded = colors, onDismissRequest = { colors = false }, containerColor = MaterialTheme.colorScheme.surface,
                properties = PopupProperties(focusable = false)) {
                TextColorPalette(document, start, end, enabled, onColor = controller::setColor, onClose = { colors = false })
            }
        }
        TextButton(onClick = { controller.clear() }, enabled = enabled && selection,
            modifier = Modifier.focusProperties { canFocus = false }.semantics { contentDescription = "Очистить форматирование" }) { Text("Tx") }
        if (onAttachFile != null) EditorMediaAction("Прикрепить файл", R.drawable.ic_attachment, enabled, onAttachFile)
        if (onInsertImage != null) EditorMediaAction("Фото +", R.drawable.ic_cover, enabled, onInsertImage, "Вставить изображение")
        if (onInsertVideo != null) EditorMediaAction("Видео +", R.drawable.ic_video, enabled, onInsertVideo, "Вставить видео")
        if (onInsertAudio != null) EditorMediaAction("Аудио +", R.drawable.ic_audio, enabled, onInsertAudio)
        if (onInsertDrawing != null) TextButton(onClick = onInsertDrawing, enabled = enabled,
            modifier = Modifier.focusProperties { canFocus = false }) { Text("Нарисовать") }
    }
}

@Composable
internal fun EditorMediaAction(label: String, icon: Int, enabled: Boolean, onClick: () -> Unit, description: String = label) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.focusProperties { canFocus = false }.semantics { contentDescription = description }) {
        Icon(painterResource(icon), null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
