package com.ontop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.Lifecycle
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.OnTopTheme
import com.ontop.app.ui.theme.Forest
import com.ontop.app.ui.theme.Ink

@Composable
fun BoardScreen(viewModel: BoardViewModel, lifecycle: Lifecycle, requestedCardId: Long? = null,
    onCardRequestHandled: () -> Unit = {}) {
    val flow = remember(viewModel, lifecycle) { viewModel.state.flowWithLifecycle(lifecycle) }
    val state by flow.collectAsState(initial = viewModel.state.value)
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val selected = state.cards.firstOrNull { it.id == selectedId }

    LaunchedEffect(requestedCardId, state.loading, state.cards, state.saving) {
        if (requestedCardId != null && !state.loading && !state.saving) {
            if (state.cards.any { it.id == requestedCardId }) {
                val openCard = {
                    selectedId = requestedCardId
                    editing = false
                    editorOpen = true
                    onCardRequestHandled()
                }
                // Release an unsaved image draft before switching away from an active editor.
                if (editorOpen && editing) viewModel.cancelImageEdit(openCard) else openCard()
            } else {
                viewModel.cardNotFound()
                onCardRequestHandled()
            }
        }
    }

    // Do not show the create form for a deleted card or before Room has loaded it.
    LaunchedEffect(state.loading, state.cards, selectedId) {
        if (!state.loading && selectedId != null && selected == null) editorOpen = false
    }
    BoardContent(state, onAdd = {
        selectedId = null
        editing = true
        editorOpen = true
        viewModel.clearError()
    }, onOpen = {
        selectedId = it.id
        editing = false
        editorOpen = true
        viewModel.clearError()
    })

    if (editorOpen && selected != null && !editing) {
        CardDetailsDialog(selected, viewModel, lifecycle, state,
            onDismiss = { editorOpen = false },
            onEdit = { editing = true; viewModel.clearError() })
    }
    if (editorOpen && editing && (selectedId == null || selected != null)) {
        LaunchedEffect(selectedId) { viewModel.beginImageEdit(selected) }
        CardEditorDialog(
            card = selected,
            saving = state.saving,
            error = state.error,
            imagePath = state.draftImagePath,
            onImageSelected = viewModel::importImage,
            onRemoveImage = viewModel::removeDraftImage,
            onPickerFailed = viewModel::imagePickerFailed,
            onDismiss = { viewModel.cancelImageEdit { editorOpen = false } },
            onSave = { title, description, size ->
                viewModel.save(selected, title, description, size) { editorOpen = false }
            },
            onDelete = { selected?.let { card -> viewModel.delete(card) { editorOpen = false } } }
        )
    }
}

@Composable
internal fun BoardContent(state: BoardState, onAdd: () -> Unit, onOpen: (CardEntity) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = Forest,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.semantics { contentDescription = "Создать карточку" }
            ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            Text("OnTop", style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            when {
                state.loading -> Text("Загружаем карточки…", Modifier.padding(20.dp))
                state.cards.isEmpty() -> Column(Modifier.padding(20.dp)) {
                    Text("Здесь появятся ваши мысли", style = MaterialTheme.typography.titleLarge)
                    Text("Создайте первую карточку с тем, что важно помнить.",
                        modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = onAdd) { Text("Создать карточку") }
                }
                else -> BoxWithConstraints(Modifier.weight(1f)) {
                    // Keep two columns on a normal phone; don't squeeze enlarged type into tiny lanes.
                    val columns = if ((maxWidth.value - 32f) / 2 >= 100f * LocalDensity.current.fontScale) 2 else 1
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize().testTag("card-board"),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp
                    ) {
                        items(state.cards, key = { it.id }, span = {
                            if (it.size == CardSize.WIDE) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane
                        }) { card -> ThoughtTile(card, onClick = { onOpen(card) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtTile(card: CardEntity, onClick: () -> Unit) {
    // Stable IDs keep the colour and minimum height unchanged when neighbouring cards change.
    val variant = Math.floorMod(card.id, 3L).toInt()
    val background = cardBackground(card.id)
    val hasImage = card.imagePath != null
    val minHeight = when {
        card.size == CardSize.WIDE -> if (hasImage) 192.dp else 144.dp
        hasImage -> (224 + variant * 24).dp
        else -> (152 + variant * 28).dp
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("card-${card.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = background,
            contentColor = if (hasImage || background == Forest) Color.White else Ink
        )
    ) {
        CardVisual(card, minHeight = minHeight)
    }
}

@Composable
private fun CardEditorDialog(
    card: CardEntity?,
    saving: Boolean,
    error: String?,
    imagePath: String?,
    onImageSelected: (android.net.Uri) -> Unit,
    onRemoveImage: () -> Unit,
    onPickerFailed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, CardSize) -> Unit,
    onDelete: () -> Unit
) {
    var title by rememberSaveable(card?.id) { mutableStateOf(card?.title.orEmpty()) }
    var description by rememberSaveable(card?.id) { mutableStateOf(card?.description.orEmpty()) }
    var size by rememberSaveable(card?.id) { mutableStateOf(card?.size ?: CardSize.NORMAL) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onImageSelected(uri)
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (card == null) "Новая карточка" else "Карточка") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, enabled = !saving,
                    label = { Text("Короткая фраза") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 5
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, enabled = !saving,
                    label = { Text("Подробный текст") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 8
                )
                imagePath?.let { path ->
                    LocalCardImage(path, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Fit)
                }
                TextButton(onClick = {
                    try { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { onPickerFailed() }
                }, enabled = !saving) {
                    Text(if (imagePath == null) "Выбрать изображение" else "Заменить изображение")
                }
                if (imagePath != null) {
                    TextButton(onClick = onRemoveImage, enabled = !saving) { Text("Удалить изображение") }
                }
                Text("Размер карточки", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = size == CardSize.NORMAL, onClick = { size = CardSize.NORMAL },
                        enabled = !saving, label = { Text("Обычная") })
                    FilterChip(selected = size == CardSize.WIDE, onClick = { size = CardSize.WIDE },
                        enabled = !saving, label = { Text("Широкая") })
                }
                if (card != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text("Удалить карточку", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, description, size) }, enabled = !saving && title.isNotBlank()) {
                Text(if (saving) "Сохраняем…" else if (card == null) "Добавить" else "Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") } }
    )
    if (confirmDelete && card != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirmDelete = false },
            title = { Text("Удалить карточку?") },
            text = { Text("Карточка «${card.title}» будет удалена. Восстановить её не получится.") },
            confirmButton = {
                TextButton(onClick = onDelete, enabled = !saving) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !saving) { Text("Отмена") }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BoardPreview() {
    OnTopTheme { BoardContent(BoardState(loading = false), onAdd = {}, onOpen = {}) }
}
