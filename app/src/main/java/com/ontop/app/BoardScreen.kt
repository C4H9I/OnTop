package com.ontop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.ontop.app.backup.BackupManager

@Composable
fun BoardScreen(viewModel: BoardViewModel, lifecycle: Lifecycle, requestedCardId: Long? = null,
    onCardRequestHandled: () -> Unit = {}) {
    val backupActive by BackupManager.active.collectAsState()
    CompositionLocalProvider(com.ontop.app.text.LocalNoteMediaActive provides !backupActive) {
        BoardScreenRoute(viewModel, lifecycle, requestedCardId, onCardRequestHandled)
    }
}

@Composable
private fun BoardScreenRoute(viewModel: BoardViewModel, lifecycle: Lifecycle, requestedCardId: Long?,
    onCardRequestHandled: () -> Unit) {
    val flow = remember(viewModel, lifecycle) { viewModel.state.flowWithLifecycle(lifecycle) }
    val state by flow.collectAsState(initial = viewModel.state.value)
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var sliderOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val backupState by viewModel.backup.state.collectAsState()
    val backupActive by BackupManager.active.collectAsState()
    val revision by viewModel.backup.revision.collectAsState()
    var seenRevision by rememberSaveable { mutableStateOf(revision) }
    LaunchedEffect(revision) {
        if (seenRevision != revision) {
            editorOpen = false; editing = false; selectedId = null; sliderOpen = false
            viewModel.sliderSession.currentId = null; viewModel.sliderSession.index = 0
            seenRevision = revision
        }
    }
    val gridState = rememberLazyStaggeredGridState()
    val selected = state.cards.firstOrNull { it.id == selectedId }

    // A widget launch waits for the user to save or discard the active draft.
    LaunchedEffect(requestedCardId, state.loading, state.cards, state.saving, editing, backupActive) {
        if (requestedCardId != null && !state.loading && !state.saving && !editing && !backupActive) {
            if (state.cards.any { it.id == requestedCardId }) {
                selectedId = requestedCardId
                editing = false
                editorOpen = true
                onCardRequestHandled()
            } else {
                viewModel.cardNotFound()
                onCardRequestHandled()
            }
        }
    }
    if (settingsOpen || backupState.recoveryRequired) {
        BackupSettingsScreen(viewModel, backupState, onClose = { settingsOpen = false })
        return
    }
    if (backupActive) BackupProgressDialog(backupState.message)

    // Do not show the create form for a deleted card or before Room has loaded it.
    LaunchedEffect(state.loading, state.cards, selectedId) {
        if (!state.loading && selectedId != null && selected == null) {
            editorOpen = false
            editing = false
        }
    }
    if ((!editorOpen || !editing) && (!sliderOpen || editorOpen)) BoardContent(state, gridState = gridState, onAdd = {
        selectedId = null
        editing = true
        editorOpen = true
        viewModel.clearError()
    }, onOpen = {
        selectedId = it.id
        editing = false
        editorOpen = true
        viewModel.clearError()
    }, onView = { viewModel.sliderSession.enter(state.cards); sliderOpen = true }, onSettings = { settingsOpen = true })

    if (sliderOpen && !editorOpen) NoteSliderScreen(state.cards, viewModel.sliderSession,
        onClose = { sliderOpen = false },
        onEdit = { selectedId = it.id; editing = true; editorOpen = true; viewModel.clearError() },
        onDetails = { selectedId = it.id; editing = false; editorOpen = true; viewModel.clearError() })

    if (editorOpen && selected != null && !editing) {
        CardDetailsDialog(selected, viewModel, lifecycle, state,
            onDismiss = { editorOpen = false },
            onEdit = { editing = true; viewModel.clearError() })
    }
    if (editorOpen && editing && (selectedId == null || selected != null)) {
        LaunchedEffect(selectedId) { viewModel.beginImageEdit(selected) }
        CardEditorRoute(
            card = selected,
            state = state,
            viewModel = viewModel,
            onDismiss = { viewModel.cancelImageEdit { editing = false; editorOpen = selected != null && !sliderOpen } },
            onSaved = { editorOpen = false; editing = false },
            onDelete = { selected?.let { card -> viewModel.delete(card) { editorOpen = false; editing = false } } }
        )
    }
}

@Composable
internal fun BoardContent(state: BoardState, onAdd: () -> Unit, onOpen: (CardEntity) -> Unit,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(), onView: () -> Unit = {},
    onSettings: () -> Unit = {}) {
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
            BoardHeader(onSettings)
            TextButton(onClick = onView, enabled = !state.loading,
                modifier = Modifier.padding(horizontal = 12.dp)) { Text("Просмотр →") }
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
                        state = gridState,
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

@Preview(showBackground = true)
@Composable
private fun BoardPreview() {
    OnTopTheme { BoardContent(BoardState(loading = false), onAdd = {}, onOpen = {}) }
}
