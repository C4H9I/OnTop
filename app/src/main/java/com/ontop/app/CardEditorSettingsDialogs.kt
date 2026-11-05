package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.Forest
import com.ontop.app.ui.theme.Ink
import com.ontop.app.ui.theme.WarmBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorDisplayDialog(imagePath: String?, size: CardSize, saving: Boolean, error: String?, chooseImage: () -> Unit,
    onRemoveImage: () -> Unit, onEditImage: () -> Unit, onSize: (CardSize) -> Unit,
    onCancel: () -> Unit, onApply: () -> Unit) {
    var fullImage by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!saving) onCancel() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WarmBackground, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentHeight = (minOf(maxHeight, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp) - 156.dp)
                .coerceAtLeast(80.dp)
            Column(Modifier.fillMaxWidth().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Отображение", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancel, enabled = !saving, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), "Закрыть отображение", tint = Forest)
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Обложка", style = MaterialTheme.typography.titleMedium, color = Ink)
                    if (imagePath == null) {
                        Surface(Modifier.fillMaxWidth().height(112.dp).testTag("editor-cover-empty"),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
                            Column(Modifier.clickable(enabled = !saving, onClick = chooseImage),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(painterResource(R.drawable.ic_cover), null, tint = Forest, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Добавить обложку", color = Forest, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
                            Box(Modifier.fillMaxWidth().heightIn(max = 180.dp).clickable(onClick = { fullImage = true })
                                .testTag("editor-image"), contentAlignment = Alignment.Center) {
                                LocalCardImage(imagePath, Modifier.fillMaxWidth().height(160.dp), ContentScale.Fit)
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = chooseImage, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(painterResource(R.drawable.ic_cover), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text("Заменить")
                            }
                            TextButton(onClick = onEditImage, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(painterResource(R.drawable.ic_draw), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text("Редактировать")
                            }
                            TextButton(onClick = onRemoveImage, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text("Удалить")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    CardSizeSelector(size, !saving, onSize)
                    Text("Изменения сохранятся вместе с заметкой.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
                    Button(onClick = onApply, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Применить") }
                }
            }
        }
    }
    if (fullImage && imagePath != null) Dialog(onDismissRequest = { fullImage = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = WarmBackground) {
            Column(Modifier.safeDrawingPadding()) {
                TextButton(onClick = { fullImage = false }) { Text("Закрыть изображение") }
                LocalCardImage(imagePath, Modifier.fillMaxWidth().weight(1f), ContentScale.Fit, maxSide = 2000)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorOtherDialog(noteId: Long, existing: Boolean, importance: Int, progress: Int?,
    automaticProgress: Boolean, saving: Boolean, onApply: (Int, Int?) -> Unit,
    onDelete: () -> Unit, onClose: () -> Unit) {
    var draftImportance by rememberSaveable(noteId) { mutableIntStateOf(importance) }
    var draftProgress by rememberSaveable(noteId) { mutableStateOf(progress) }
    var detailedOpen by rememberSaveable(noteId) { mutableStateOf(false) }
    if (!detailedOpen) ModalBottomSheet(onDismissRequest = { if (!saving) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WarmBackground, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentHeight = (minOf(maxHeight, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp) - 156.dp)
                .coerceAtLeast(80.dp)
            Column(Modifier.fillMaxWidth().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Другие настройки", style = MaterialTheme.typography.titleLarge, color = Ink,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, enabled = !saving, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), "Закрыть другие настройки", tint = Forest)
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Важность: $draftImportance%", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Slider(draftImportance.toFloat(), {
                        draftImportance = (kotlin.math.round(it / 5f).toInt() * 5).coerceIn(0, 100)
                    }, valueRange = 0f..100f, steps = 19, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().testTag("editor-importance")
                            .semantics { contentDescription = "Важность заметки" })
                    Text("Как часто возвращать внимание к этой мысли. Это не срочность задачи.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { detailedOpen = true }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text("Важность и готовность · ${draftProgress?.let { "$it%" } ?: "—"}",
                            modifier = Modifier.weight(1f))
                        Text("›")
                    }
                    Text("Заметка хранится только на этом устройстве.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (existing) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                        TextButton(onClick = onDelete, enabled = !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Удалить карточку", color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
                    Button(onClick = { onApply(draftImportance, draftProgress); onClose() },
                        enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Применить") }
                }
            }
        }
    }
    if (detailedOpen) NotePercentagesDialog(noteId, draftImportance, draftProgress,
        onDismiss = { detailedOpen = false }, onSave = { newImportance, newProgress ->
            draftImportance = newImportance
            if (!automaticProgress) draftProgress = newProgress
            detailedOpen = false
        }, saving = saving, automaticProgress = automaticProgress)
}
