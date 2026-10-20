package com.ontop.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardSize

@Composable
internal fun EditorDisplayDialog(imagePath: String?, size: CardSize, saving: Boolean, chooseImage: () -> Unit,
    onRemoveImage: () -> Unit, onSize: (CardSize) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!saving) onClose() }, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Отображение") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Обложка", style = MaterialTheme.typography.titleMedium)
                if (imagePath != null) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    LocalCardImage(imagePath, Modifier.fillMaxWidth().height(180.dp).testTag("editor-image"), ContentScale.Fit)
                }
                OutlinedButton(onClick = chooseImage, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
                    Text(if (imagePath == null) "Выбрать изображение" else "Заменить изображение")
                }
                if (imagePath != null) TextButton(onClick = onRemoveImage, enabled = !saving) { Text("Удалить изображение") }
                CardSizeSelector(size, !saving, onSize)
            }
        }, confirmButton = { TextButton(onClick = onClose, enabled = !saving) { Text("Готово") } })
}

@Composable
internal fun EditorOtherDialog(existing: Boolean, importance: Int, progress: Int?, saving: Boolean,
    onImportance: (Int) -> Unit, onPercentages: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Другие настройки") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Заметка хранится только на этом устройстве.")
                Text("Важность: $importance%", style = MaterialTheme.typography.titleMedium)
                Slider(importance.toFloat(), { onImportance((kotlin.math.round(it / 5f).toInt() * 5).coerceIn(0, 100)) },
                    valueRange = 0f..100f, steps = 19, enabled = !saving,
                    modifier = Modifier.testTag("editor-importance").semantics { contentDescription = "Важность заметки" })
                Text("Как часто возвращать внимание к этой мысли. Это не срочность задачи.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onPercentages, enabled = !saving) { Text("Важность и готовность · ${progress?.let { "$it%" } ?: "—"}") }
                if (existing) TextButton(onClick = onDelete, enabled = !saving) { Text("Удалить карточку", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Готово") } })
}
