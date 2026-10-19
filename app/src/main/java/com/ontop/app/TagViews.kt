package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ontop.app.data.*

@Composable
internal fun TopicFilter(state: BoardState, onSelect: (Long?) -> Unit, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).testTag("topic-filter"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = state.selectedTagId == null, onClick = { onSelect(null) },
                label = { Text("Все") }, modifier = Modifier.testTag("topic-all")) }
            items(state.tags, key = { it.id }) { tag ->
                FilterChip(selected = state.selectedTagId == tag.id, onClick = { onSelect(tag.id) },
                    label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    modifier = Modifier.testTag("topic-${tag.id}"))
            }
        }
        TextButton(onClick = onManage, enabled = !state.loading && !state.saving,
            modifier = Modifier.semantics { contentDescription = "Управление темами" }) { Text("Темы") }
    }
}

@Composable
internal fun TagPickerDialog(tags: List<TagEntity>, selectedIds: List<Long>, newNames: List<String>, enabled: Boolean,
    onChange: (List<Long>, List<String>) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val name = runCatching { cleanTagName(query) }.getOrNull()
    val key = name?.let(::tagNameKey)
    val matches = tags.filter { key == null || it.normalizedName.contains(key) }
    val duplicate = key != null && (tags.any { it.normalizedName == key } || newNames.any { tagNameKey(it) == key })
    AlertDialog(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Темы заметки") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("Можно выбрать несколько тем или оставить заметку без тем.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.filter { it.id in selectedIds }.forEach { tag ->
                    SelectedTopic(tag.name, enabled) { onChange(selectedIds - tag.id, newNames) }
                }
                newNames.forEach { value -> SelectedTopic(value, enabled) { onChange(selectedIds, newNames - value) } }
            }
            OutlinedTextField(query, { query = it }, enabled = enabled, singleLine = true,
                label = { Text("Найти или создать тему") }, modifier = Modifier.fillMaxWidth().testTag("tag-query"))
            if (query.isNotBlank() && name == null) Text("Название темы — от 1 до 100 символов", color = MaterialTheme.colorScheme.error)
            if (name != null && !duplicate) TextButton(enabled = enabled, onClick = {
                onChange(selectedIds, newNames + name); query = ""
            }, modifier = Modifier.testTag("tag-create-draft")) { Text("Создать «$name»", maxLines = 2) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                matches.take(30).forEach { tag ->
                    FilterChip(selected = tag.id in selectedIds, enabled = enabled,
                        onClick = { onChange(if (tag.id in selectedIds) selectedIds - tag.id else selectedIds + tag.id, newNames) },
                        label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                        modifier = Modifier.testTag("tag-option-${tag.id}"))
                }
            }
            if (matches.size > 30) Text("Уточните поиск, чтобы увидеть остальные темы.")
            Text("Новые темы и выбор сохранятся вместе с заметкой.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Готово") } })
}

@Composable
private fun SelectedTopic(name: String, enabled: Boolean, onRemove: () -> Unit) {
    InputChip(selected = true, enabled = enabled, onClick = onRemove,
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
        trailingIcon = { Text("×") }, modifier = Modifier.semantics { contentDescription = "Убрать тему $name" })
}

@Composable
internal fun TagManagementDialog(state: BoardState, onCreate: (String, () -> Unit) -> Unit,
    onRename: (Long, String, () -> Unit) -> Unit, onDelete: (Long, () -> Unit) -> Unit, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val validName = runCatching { cleanTagName(name) }.getOrNull()
    val duplicate = validName != null && state.tags.any { it.id != editingId && it.normalizedName == tagNameKey(validName) }
    AlertDialog(onDismissRequest = { if (!state.saving) onClose() }, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Темы") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text(if (editingId == null) "Новая тема" else "Название темы") },
                enabled = !state.saving, singleLine = true, isError = duplicate || name.isNotBlank() && validName == null,
                modifier = Modifier.fillMaxWidth().testTag("tag-manager-name"))
            if (duplicate) Text("Тема с таким названием уже существует", color = MaterialTheme.colorScheme.error)
            if (name.isNotBlank() && validName == null) Text("Название темы — от 1 до 100 символов", color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(enabled = !state.saving && validName != null && !duplicate, onClick = {
                    val saved = { name = ""; editingId = null }
                    val id = editingId
                    if (id == null) onCreate(validName!!, saved) else onRename(id, validName!!, saved)
                }, modifier = Modifier.testTag("tag-manager-save")) { Text(if (editingId == null) "Создать" else "Сохранить") }
                if (editingId != null) TextButton(enabled = !state.saving, onClick = { editingId = null; name = "" }) { Text("Отмена") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.tags, key = { it.id }) { tag ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tag.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = !state.saving, onClick = { editingId = tag.id; name = tag.name },
                            modifier = Modifier.semantics { contentDescription = "Переименовать тему ${tag.name}" }) { Text("Изменить") }
                        TextButton(enabled = !state.saving, onClick = {
                            if (state.cardTags.any { it.tagId == tag.id }) deleteId = tag.id else onDelete(tag.id) {}
                        }, modifier = Modifier.semantics { contentDescription = "Удалить тему ${tag.name}" }) { Text("Удалить") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = !state.saving, onClick = onClose) { Text("Закрыть") } })
    state.tags.firstOrNull { it.id == deleteId }?.let { tag ->
        AlertDialog(onDismissRequest = { if (!state.saving) deleteId = null }, containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить тему «${tag.name}»?") },
            text = { Text("Связи с заметками будут удалены. Сами заметки останутся.") },
            confirmButton = { TextButton(enabled = !state.saving, onClick = { onDelete(tag.id) { deleteId = null } }) { Text("Удалить тему") } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { deleteId = null }) { Text("Отмена") } })
    }
}
