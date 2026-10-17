package com.ontop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.Lifecycle
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.OnTopTheme

private val cardColors = listOf(
    Color(0xFFE4EBDD), Color(0xFFE6E1EF), Color(0xFFF3E6D3),
    Color(0xFFDDEBEC), Color(0xFFF0DFDF), Color(0xFFE6E8D9)
)

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
private fun BoardContent(state: BoardState, onAdd: () -> Unit, onOpen: (CardEntity) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.semantics { contentDescription = "Создать карточку" }
            ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            Text("OnTop", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
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
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.cards, key = { it.id }, span = {
                        GridItemSpan(if (it.size == CardSize.WIDE) 2 else 1)
                    }) { card -> ThoughtTile(card, onClick = { onOpen(card) }) }
                }
            }
        }
    }
}

@Composable
private fun ThoughtTile(card: CardEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (card.size == CardSize.WIDE) 132.dp else 176.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColors[((card.id - 1) % cardColors.size).toInt()],
            contentColor = Color(0xFF252A28)
        )
    ) {
        card.imagePath?.let { path ->
            LocalCardImage(path, Modifier.fillMaxWidth().height(if (card.size == CardSize.WIDE) 160.dp else 144.dp),
                contentScale = ContentScale.Crop, maxSide = 600)
        }
        // An opaque surface keeps the title readable for every image, including missing files.
        Text(card.title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().background(cardColors[((card.id - 1) % cardColors.size).toInt()]).padding(20.dp))
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
