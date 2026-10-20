package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import com.ontop.app.data.TagEntity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardStatus
import com.ontop.app.data.CardCommentEntity
import com.ontop.app.data.ReminderEntity
import com.ontop.app.reminders.summary
import com.ontop.app.text.RichTextCodec
import com.ontop.app.text.annotated
import com.ontop.app.text.MultimediaNoteView
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
internal fun CardDetailsDialog(
    card: CardEntity,
    viewModel: BoardViewModel,
    lifecycle: Lifecycle,
    state: BoardState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    var sharing by remember(card.id) { mutableStateOf(false) }
    var reminderOpen by rememberSaveable(card.id) { mutableStateOf(false) }
    var percentagesOpen by rememberSaveable(card.id) { mutableStateOf(false) }
    var reminderLoaded by remember(card.id) { mutableStateOf(false) }
    var reminderError by remember(card.id) { mutableStateOf(false) }
    val reminderFlow = remember(card.id, viewModel, lifecycle) {
        viewModel.observeReminder(card.id).catch { reminderError = true }.flowWithLifecycle(lifecycle)
    }
    val reminder by produceState<ReminderEntity?>(null, card.id, reminderFlow) {
        value = null
        reminderFlow.collect { value = it; reminderLoaded = true }
    }
    val currentReminder = reminder?.takeIf { it.cardId == card.id }
    var summaryNow by remember(card.id) { mutableStateOf(Instant.now()) }
    LaunchedEffect(currentReminder?.pausedUntil) {
        summaryNow = Instant.now()
        currentReminder?.pausedUntil?.let { until ->
            val remaining = until - System.currentTimeMillis()
            if (remaining > 0) { kotlinx.coroutines.delay(remaining); summaryNow = Instant.now() }
        }
    }
    val reminderStatus = when {
        reminderError -> "Не удалось загрузить напоминание"
        !reminderLoaded -> "Загрузка напоминания…"
        card.status != CardStatus.ACTIVE -> "Напоминания приостановлены до восстановления заметки"
        currentReminder != null -> currentReminder!!.summary(summaryNow)
        else -> "Напоминание выключено"
    }
    var loadError by remember(card.id) { mutableStateOf(false) }
    var loaded by remember(card.id) { mutableStateOf(false) }
    val flow = remember(card.id, viewModel, lifecycle) {
        viewModel.observeComments(card.id)
            .catch { loadError = true }
            .flowWithLifecycle(lifecycle)
    }
    val comments by produceState<List<CardCommentEntity>>(emptyList(), flow) {
        value = emptyList()
        flow.collect { value = it; loaded = true }
    }
    Dialog(onDismissRequest = { if (!state.saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightSurface = true // The reading surface is white in both application themes.
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSurface
                    isAppearanceLightNavigationBars = lightSurface
                }
            }
        }
        key(card.id) {
            CardDetailsContent(card, comments, loaded, loadError, state.saving, state.error,
                onAddComment = { text, success -> viewModel.addComment(card.id, text, success) },
                onDeleteComment = viewModel::deleteComment, onDismiss = onDismiss, onEdit = onEdit,
                onReminder = { reminderOpen = true }, reminderStatus = reminderStatus,
                onPercentages = { viewModel.clearError(); percentagesOpen = true },
                tags = state.tags.filter { tag -> state.cardTags.any { it.cardId == card.id && it.tagId == tag.id } },
                onArchive = { viewModel.changeStatus(card, CardStatus.ARCHIVED, onDismiss) },
                onRestore = { viewModel.changeStatus(card, CardStatus.ACTIVE, onDismiss) },
                onTrash = { viewModel.delete(card, onDismiss) },
                onPermanentDelete = { viewModel.permanentlyDelete(card, onDismiss) },
                sharing = sharing, onShare = {
                    sharing = true
                    shareScope.launch {
                        try {
                            val prepared = com.ontop.app.sharing.OutgoingShare.prepare(context, card)
                            if (prepared.imageUnavailable) android.widget.Toast.makeText(context,
                                "Не удалось приложить изображение. Доступна отправка текста.", android.widget.Toast.LENGTH_LONG).show()
                            context.startActivity(android.content.Intent.createChooser(prepared.intent, "Поделиться заметкой"))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                        } catch (_: Exception) { android.widget.Toast.makeText(context,
                            "Не удалось открыть меню «Поделиться».", android.widget.Toast.LENGTH_LONG).show()
                        } finally { sharing = false }
                    }
                })
        }
    }
    if (reminderOpen) key(card.id) {
        ReminderSettingsDialog(card.id, viewModel) { reminderOpen = false }
    }
    if (percentagesOpen) NotePercentagesDialog(card.id, card.importance, card.progress,
        onDismiss = { percentagesOpen = false },
        onSave = { importance, progress -> viewModel.savePercentages(card.id, importance, progress) { percentagesOpen = false } },
        saving = state.saving, error = state.error)
}

@Composable
internal fun CardDetailsContent(
    card: CardEntity,
    comments: List<CardCommentEntity>,
    loaded: Boolean,
    loadError: Boolean,
    saving: Boolean,
    error: String?,
    onAddComment: (String, (Long) -> Unit) -> Unit,
    onDeleteComment: (Long, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onReminder: (() -> Unit)? = null,
    reminderStatus: String = "Напоминание выключено",
    onShare: (() -> Unit)? = null, sharing: Boolean = false,
    onArchive: (() -> Unit)? = null, onRestore: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null, onPermanentDelete: (() -> Unit)? = null,
    onPercentages: (() -> Unit)? = null,
    tags: List<TagEntity> = emptyList()
) {
    var menu by remember { mutableStateOf(false) }
    var confirmPermanent by rememberSaveable(card.id) { mutableStateOf(false) }
    if (confirmPermanent) AlertDialog(onDismissRequest = { confirmPermanent = false },
        title = { Text("Удалить заметку навсегда?") },
        text = { Text("Заметка, комментарии и неиспользуемые изображения будут удалены без возможности восстановления.") },
        confirmButton = { TextButton(enabled = !saving, onClick = { confirmPermanent = false; onPermanentDelete?.invoke() }) { Text("Удалить навсегда") } },
        dismissButton = { TextButton(onClick = { confirmPermanent = false }) { Text("Отмена") } })
    var draft by rememberSaveable(card.id) { mutableStateOf("") }
    var deletingId by rememberSaveable(card.id) { mutableStateOf<Long?>(null) }
    var pendingId by rememberSaveable(card.id) { mutableStateOf<Long?>(null) }
    var jumpTargetId by rememberSaveable(card.id) { mutableStateOf<Long?>(null) }
    var followSubmission by remember { mutableStateOf(false) }
    var submitAnchor by remember { mutableStateOf(0 to 0) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

    // Only follow this screen's successful submission, after Room has emitted that exact ID.
    // Incoming changes and reading older entries never unconditionally move the viewport.
    LaunchedEffect(pendingId, comments) {
        val id = pendingId ?: return@LaunchedEffect
        val index = comments.indexOfFirst { it.id == id }
        if (index >= 0) {
            if (followSubmission && submitAnchor == (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
                && !list.isScrollInProgress) {
                list.scrollToItem(index + 2)
                jumpTargetId = null
            }
            pendingId = null
        }
    }
    val submit = {
        if (!saving && draft.isNotBlank()) {
            followSubmission = !list.canScrollForward
            submitAnchor = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            onAddComment(draft) { id ->
                draft = ""
                jumpTargetId = id
                pendingId = id
            }
        }
    }

    MaterialTheme(colorScheme = noteReadingColors()) {
    Surface(modifier.fillMaxSize().testTag("card-details"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Закрыть карточку")
                }
                TextButton(onClick = onEdit, enabled = !saving && card.status != CardStatus.TRASHED, modifier = Modifier.weight(1f)) {
                    Text("Редактировать")
                }
                if (onShare != null) IconButton(onClick = onShare, enabled = !saving && !sharing,
                    modifier = Modifier.testTag("share-note")) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = "Поделиться",
                        tint = MaterialTheme.colorScheme.primary)
                }
                if (onReminder != null) IconButton(onClick = onReminder, enabled = !saving,
                    modifier = Modifier.testTag("reminder-settings")) {
                    Icon(painterResource(R.drawable.ic_bell), contentDescription = "Настроить напоминание",
                        tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !saving) {
                        Icon(painterResource(R.drawable.ic_more), "Меню заметки")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (card.status != CardStatus.TRASHED) DropdownMenuItem(text = { Text("Редактировать") },
                            onClick = { menu = false; onEdit() })
                        if (onShare != null) DropdownMenuItem(text = { Text("Поделиться") }, enabled = !sharing,
                            onClick = { menu = false; onShare() })
                        if (card.status == CardStatus.ACTIVE && onArchive != null) DropdownMenuItem(text = { Text("Архивировать") },
                            onClick = { menu = false; onArchive() })
                        if (card.status != CardStatus.ACTIVE && onRestore != null) DropdownMenuItem(
                            text = { Text(if (card.status == CardStatus.ARCHIVED) "Восстановить из архива" else "Восстановить из корзины") },
                            onClick = { menu = false; onRestore() })
                        if (card.status != CardStatus.TRASHED && onTrash != null) DropdownMenuItem(text = { Text("Удалить") },
                            onClick = { menu = false; onTrash() })
                        if (card.status == CardStatus.TRASHED && onPermanentDelete != null) DropdownMenuItem(text = { Text("Удалить навсегда") },
                            onClick = { menu = false; confirmPermanent = true })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("reflection-list"), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                item(key = "summary") {
                    Column {
                        NoteStatusWithTopics(card, tags, "details-status-${card.id}",
                            enabled = !saving && card.status != CardStatus.TRASHED, onClick = onPercentages,
                            modifier = Modifier.padding(bottom = 12.dp))
                        NoteReadingTitle(card.title, Modifier.testTag("details-title"))
                        val document = RichTextCodec.decode(card.description, card.formatting)
                        if (document.media.isNotEmpty()) MultimediaNoteView(document)
                        else if (card.description.isNotBlank()) {
                            Text(RichTextCodec.decode(card.description, card.formatting).annotated(MaterialTheme.typography.bodyLarge.fontSize,
                                MaterialTheme.colorScheme.background.luminance() < 0.5f), style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).testTag("card-description"))
                        }
                        if (onReminder != null) TextButton(onClick = onReminder, enabled = !saving,
                            modifier = Modifier.fillMaxWidth().testTag("reminder-status"),
                            contentPadding = PaddingValues(vertical = 8.dp)) {
                            Text(reminderStatus, modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item(key = "history-heading") {
                    Column(Modifier.padding(top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("История размышлений · ${if (loaded && !loadError) comments.size else "—"}",
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("history-heading"))
                        when {
                            loadError -> Text("Не удалось загрузить историю. Откройте карточку повторно.",
                                color = MaterialTheme.colorScheme.error)
                            !loaded -> Text("Загружаем историю…")
                            comments.isEmpty() -> Text("Здесь сохранится история ваших размышлений. Добавьте первую запись в поле внизу.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(comments, key = { "comment-${it.id}" }) { comment ->
                    Column(Modifier.fillMaxWidth().testTag("comment-${comment.id}")) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                        Text(formatter.format(Instant.ofEpochMilli(comment.createdAt)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { deletingId = comment.id }, enabled = !saving,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            modifier = Modifier.testTag("delete-comment-${comment.id}")) {
                            Text("Удалить комментарий", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                    val targetIndex = comments.indexOfFirst { it.id == jumpTargetId }
                    if (targetIndex >= 0) {
                        TextButton(onClick = {
                            scope.launch {
                                list.scrollToItem(targetIndex + 2)
                                jumpTargetId = null
                            }
                        }, modifier = Modifier.align(Alignment.End)) { Text("К последней записи") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp).heightIn(max = 80.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())) }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, enabled = !saving,
                            placeholder = { Text("Напишите…") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).testTag("comment-input")
                                .semantics { contentDescription = "Новое размышление" }, maxLines = 3)
                        FilledIconButton(onClick = submit, enabled = !saving && draft.isNotBlank(),
                            modifier = Modifier.size(48.dp).testTag("send-comment")) {
                            Icon(painterResource(R.drawable.ic_send), contentDescription = if (saving) "Сохраняем…" else "Добавить комментарий")
                        }
                    }
                }
            }
        }
    }
    }
    deletingId?.let { id ->
        AlertDialog(onDismissRequest = { if (!saving) deletingId = null },
            title = { Text("Удалить комментарий?") },
            text = { Text("Комментарий будет удалён. Восстановить его не получится.") },
            confirmButton = {
                TextButton(onClick = { onDeleteComment(id) { deletingId = null } },
                    enabled = !saving) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }, enabled = !saving) { Text("Отмена") }
            })
    }
}
