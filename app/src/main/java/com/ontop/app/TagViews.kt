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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import com.ontop.app.data.*
import com.ontop.app.ui.theme.Forest
import com.ontop.app.ui.theme.Ink
import com.ontop.app.ui.theme.WarmBackground
import androidx.compose.ui.graphics.Color

@Composable
internal fun TopicFilter(state: BoardState, onSelect: (Long?) -> Unit, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).testTag("topic-filter"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = state.selectedTagId == null, onClick = { onSelect(null) },
                label = { Text("Все") }, modifier = Modifier.testTag("topic-all"),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Forest, selectedLabelColor = Color.White)) }
            items(state.tags, key = { it.id }) { tag ->
                FilterChip(selected = state.selectedTagId == tag.id, onClick = { onSelect(tag.id) },
                    label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    modifier = Modifier.testTag("topic-${tag.id}"),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Forest, selectedLabelColor = Color.White))
            }
        }
        TextButton(onClick = onManage, enabled = !state.loading && !state.saving,
            modifier = Modifier.semantics { contentDescription = "Управление темами" }) { Text("Темы") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagPickerDialog(tags: List<TagEntity>, selectedIds: List<Long>, newNames: List<String>, enabled: Boolean,
    onChange: (List<Long>, List<String>) -> Unit, onClose: () -> Unit) {
    var draftIds by rememberSaveable { mutableStateOf(selectedIds) }
    var draftNames by rememberSaveable { mutableStateOf(newNames) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val name = runCatching { cleanTagName(query) }.getOrNull()
    val key = name?.let(::tagNameKey)
    val duplicate = key != null && (tags.any { it.normalizedName == key } || draftNames.any { tagNameKey(it) == key })
    ModalBottomSheet(onDismissRequest = { if (enabled) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WarmBackground, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), dragHandle = null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentHeight = (minOf(maxHeight, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp) - 156.dp)
                .coerceAtLeast(80.dp)
            Column(Modifier.fillMaxWidth().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Темы", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), "Закрыть темы", tint = Forest)
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выберите темы заметки", style = MaterialTheme.typography.titleMedium, color = Ink)
                    if (tags.isEmpty() && draftNames.isEmpty()) Text("Тем пока нет. Создайте первую тему.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { tag ->
                            val selected = tag.id in draftIds
                            FilterChip(selected = selected, enabled = enabled,
                                onClick = { draftIds = if (selected) draftIds - tag.id else draftIds + tag.id },
                                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("tag-option-${tag.id}")
                                    .semantics {
                                        this.selected = selected
                                        if (selected) contentDescription = "Убрать тему ${tag.name}"
                                    },
                                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    selectedContainerColor = Forest, selectedLabelColor = Color.White),
                                border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected,
                                    borderColor = Color.Transparent, selectedBorderColor = Color.Transparent))
                        }
                        draftNames.forEach { value ->
                            FilterChip(selected = true, enabled = enabled, onClick = { draftNames = draftNames - value },
                                label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp)) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Убрать тему $value" },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Forest,
                                    selectedLabelColor = Color.White),
                                border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = true,
                                    borderColor = Color.Transparent, selectedBorderColor = Color.Transparent))
                        }
                    }
                    if (!adding) TextButton(onClick = { adding = true }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Новая тема") }
                    else {
                        OutlinedTextField(query, { query = it }, enabled = enabled, singleLine = true,
                            label = { Text("Название темы") }, isError = query.isNotBlank() && (name == null || duplicate),
                            modifier = Modifier.fillMaxWidth().testTag("tag-query"))
                        if (duplicate) Text("Тема с таким названием уже существует", color = MaterialTheme.colorScheme.error)
                        else if (query.isNotBlank() && name == null) Text("Название темы — от 1 до 100 символов",
                            color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { query = ""; adding = false }, enabled = enabled,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Отменить ввод") }
                            TextButton(onClick = {
                                draftNames = draftNames + name!!; query = ""; adding = false
                            }, enabled = enabled && name != null && !duplicate,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("tag-create-draft")) { Text("Создать") }
                        }
                    }
                    Text("Новые темы сохранятся вместе с заметкой.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
                    Button(onClick = { onChange(draftIds, draftNames); onClose() }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Применить") }
                }
            }
        }
    }
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
