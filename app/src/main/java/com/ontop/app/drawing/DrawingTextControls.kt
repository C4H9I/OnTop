package com.ontop.app.drawing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DrawingTextParameters(text: DrawingText?, busy: Boolean, onEdit: () -> Unit,
    onDelete: () -> Unit, onChange: (DrawingText) -> Unit) {
    if (text == null) {
        Text("Нажмите на холст, чтобы добавить или выбрать надпись", Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall)
    } else {
        var fontSize by remember(text) { mutableFloatStateOf(text.fontSize) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit, enabled = !busy) { Text("Изменить") }
            TextButton(onClick = onDelete, enabled = !busy) { Text("Удалить") }
            Text("${fontSize.toInt()}")
            Slider(fontSize, { fontSize = it }, enabled = !busy, valueRange = 8f..200f,
                onValueChangeFinished = { onChange(text.copy(fontSize = fontSize)) },
                modifier = Modifier.weight(1f).testTag("drawing-text-size"))
        }
    }
}

@Composable
internal fun DrawingTextDialog(value: DrawingText, onDismiss: () -> Unit, onSave: (DrawingText) -> Unit) {
    var content by remember(value.id) { mutableStateOf(value.text) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(value.id) { focus.requestFocus(); keyboard?.show() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Надпись") }, text = {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(content, { if (it.length <= 10000) content = it }, label = { Text("Текст") },
                minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("drawing-text-input"))
        }
    }, confirmButton = {
        TextButton(onClick = { keyboard?.hide(); onSave(value.copy(text = content)) }, enabled = content.isNotBlank()) { Text("Добавить / сохранить") }
    }, dismissButton = { TextButton(onClick = { keyboard?.hide(); onDismiss() }) { Text("Отмена") } })
}
