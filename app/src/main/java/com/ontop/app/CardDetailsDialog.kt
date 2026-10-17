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
import androidx.compose.ui.graphics.luminance
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
import com.ontop.app.data.CardCommentEntity
import java.time.Instant
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
        val lightSurface = MaterialTheme.colorScheme.background.luminance() > 0.5f
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
                onDeleteComment = viewModel::deleteComment, onDismiss = onDismiss, onEdit = onEdit)
        }
    }
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
    modifier: Modifier = Modifier
) {
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

    Surface(modifier.fillMaxSize().testTag("card-details"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Закрыть карточку")
                }
                TextButton(onClick = onEdit, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text("Редактировать")
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("reflection-list"), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                item(key = "summary") {
                    Column {
                        CardVisual(card, modifier = Modifier.testTag("details-hero"),
                            minHeight = if (card.imagePath != null) 280.dp else 200.dp,
                            titleStyle = MaterialTheme.typography.headlineSmall, maxImageSide = 1600)
                        if (card.description.isNotBlank()) {
                            Text(card.description, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).testTag("card-description"))
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
