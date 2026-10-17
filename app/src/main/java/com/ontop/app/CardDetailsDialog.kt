package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import com.ontop.app.data.CardEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.catch

@Composable
internal fun CardDetailsDialog(
    card: CardEntity,
    viewModel: BoardViewModel,
    lifecycle: Lifecycle,
    state: BoardState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    var loadError by remember(card.id) { mutableStateOf(false) }
    var loaded by remember(card.id) { mutableStateOf(false) }
    val flow = remember(card.id, viewModel, lifecycle) {
        viewModel.observeComments(card.id)
            .catch { loadError = true }
            .flowWithLifecycle(lifecycle)
    }
    val comments by produceState<List<com.ontop.app.data.CardCommentEntity>>(emptyList(), flow) {
        flow.collect { value = it; loaded = true }
    }
    var draft by rememberSaveable(card.id) { mutableStateOf("") }
    var deletingId by rememberSaveable(card.id) { mutableStateOf<Long?>(null) }
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

    Dialog(onDismissRequest = { if (!state.saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Закрыть") }
                    TextButton(onClick = onEdit, enabled = !state.saving) { Text("Редактировать") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(card.title, style = MaterialTheme.typography.headlineMedium) }
                    card.imagePath?.let { path ->
                        item { LocalCardImage(path, Modifier.fillMaxWidth().height(280.dp),
                            contentScale = ContentScale.Fit, maxSide = 1600) }
                    }
                    if (card.description.isNotEmpty()) {
                        item { Text(card.description, style = MaterialTheme.typography.bodyLarge) }
                    }
                    item { Text("История размышлений", style = MaterialTheme.typography.titleLarge) }
                    when {
                        loadError -> item { Text("Не удалось загрузить историю. Откройте карточку повторно.",
                            color = MaterialTheme.colorScheme.error) }
                        !loaded -> item { Text("Загружаем историю…") }
                        comments.isEmpty() -> item { Text("Добавьте первое размышление к этой карточке.") }
                    }
                    items(comments, key = { it.id }) { comment ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(formatter.format(Instant.ofEpochMilli(comment.createdAt)),
                                    style = MaterialTheme.typography.labelMedium)
                                Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = { deletingId = comment.id }, enabled = !state.saving) {
                                    Text("Удалить комментарий")
                                }
                            }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    enabled = !state.saving, label = { Text("Новое размышление") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4)
                TextButton(onClick = { viewModel.addComment(card.id, draft) { draft = "" } },
                    enabled = !state.saving && draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.saving) "Сохраняем…" else "Отправить")
                }
            }
        }
    }
    deletingId?.let { id ->
        AlertDialog(onDismissRequest = { if (!state.saving) deletingId = null },
            title = { Text("Удалить комментарий?") },
            text = { Text("Комментарий будет удалён. Восстановить его не получится.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteComment(id) { deletingId = null } },
                    enabled = !state.saving) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }, enabled = !state.saving) { Text("Отмена") }
            })
    }
}
