package com.ontop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.ontop.app.data.NoteType
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
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
import com.ontop.app.sharing.IncomingShare

@Composable
fun BoardScreen(viewModel: BoardViewModel, lifecycle: Lifecycle, requestedCardId: Long? = null,
    onCardRequestHandled: () -> Unit = {}, incomingShare: IncomingShare? = null, onShareHandled: () -> Unit = {},
    requestFromWidget: Boolean = false) {
    val backupActive by BackupManager.active.collectAsState()
    CompositionLocalProvider(com.ontop.app.text.LocalNoteMediaActive provides !backupActive) {
        BoardScreenRoute(viewModel, lifecycle, requestedCardId, onCardRequestHandled, incomingShare, onShareHandled, requestFromWidget)
    }
}

@Composable
private fun BoardScreenRoute(viewModel: BoardViewModel, lifecycle: Lifecycle, requestedCardId: Long?,
    onCardRequestHandled: () -> Unit, incomingShare: IncomingShare?, onShareHandled: () -> Unit, requestFromWidget: Boolean) {
    val flow = remember(viewModel, lifecycle) { viewModel.state.flowWithLifecycle(lifecycle) }
    val state by flow.collectAsState(initial = viewModel.state.value)
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var newNoteType by rememberSaveable { mutableStateOf(NoteType.NOTE) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var sliderOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tagsOpen by rememberSaveable { mutableStateOf(false) }
    var collection by rememberSaveable { mutableStateOf<com.ontop.app.data.CardStatus?>(null) }
    val percentNoteId = rememberSaveable { mutableStateOf<Long?>(null) }
    var sharedDraft by rememberSaveable { mutableStateOf<IncomingShare?>(null) }
    var sharedImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    val sharedInlineImage by viewModel.sharedInlineImage.collectAsState()
    val backupState by viewModel.backup.state.collectAsState()
    val backupActive by BackupManager.active.collectAsState()
    val revision by viewModel.backup.revision.collectAsState()
    var seenRevision by rememberSaveable { mutableStateOf(revision) }
    LaunchedEffect(revision) {
        if (seenRevision != revision) {
            editorOpen = false; editing = false; selectedId = null; sliderOpen = false; tagsOpen = false
            collection = null
            percentNoteId.value = null
            sharedDraft = null; sharedImagePath = null
            viewModel.sliderSession.currentId = null; viewModel.sliderSession.index = 0
            seenRevision = revision
        }
    }
    val gridState = rememberLazyStaggeredGridState()
    val selected = state.storedCards.firstOrNull { it.id == selectedId }
    val filteredCards = remember(state.cards, state.cardTags, state.selectedTagId) {
        filterCardsByTag(state.cards, state.cardTags, state.selectedTagId)
    }

    // A widget launch waits for the user to save or discard the active draft.
    LaunchedEffect(requestedCardId, state.loading, state.cards, state.saving, editing, backupActive, percentNoteId.value) {
        if (requestedCardId != null && !state.loading && !state.saving && !editing && !backupActive && percentNoteId.value == null) {
            if (state.cards.any { it.id == requestedCardId }) {
                selectedId = requestedCardId
                editing = false
                editorOpen = true
                if (requestFromWidget) viewModel.recordOpening(requestedCardId, com.ontop.app.data.NoteInteractionType.WIDGET_OPENED)
                onCardRequestHandled()
            } else {
                viewModel.cardNotFound()
                onCardRequestHandled()
            }
        }
    }
    val context = LocalContext.current
    state.storedCards.firstOrNull { it.id == percentNoteId.value }?.let { note ->
        NotePercentagesDialog(note.id, note.importance, note.progress, onDismiss = { percentNoteId.value = null },
            onSave = { importance, progress -> viewModel.savePercentages(note.id, importance, progress) { percentNoteId.value = null } },
            saving = state.saving, error = state.error, automaticProgress = note.noteType == NoteType.CHECKLIST)
    }
    LaunchedEffect(incomingShare?.id) {
        if (incomingShare != null && (editorOpen || percentNoteId.value != null)) android.widget.Toast.makeText(context,
            "Материал получен. Закройте текущую заметку, чтобы открыть его.", android.widget.Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(incomingShare?.id, state.loading, state.saving, editorOpen, backupActive, backupState.recoveryRequired, percentNoteId.value) {
        if (incomingShare != null && !state.loading && !state.saving && !editorOpen && !backupActive && !backupState.recoveryRequired && percentNoteId.value == null) {
            selectedId = null; sharedDraft = incomingShare; sharedImagePath = null
            collection = null
            settingsOpen = false; tagsOpen = false; sliderOpen = false
            editing = true; editorOpen = true
            viewModel.clearError()
            onShareHandled()
        }
    }
    if (settingsOpen || backupState.recoveryRequired) {
        BackupSettingsScreen(viewModel, backupState, onClose = { settingsOpen = false })
        return
    }
    if (backupActive) BackupProgressDialog(backupState.message)

    // Do not show the create form for a deleted card or before Room has loaded it.
    LaunchedEffect(state.loading, state.cards, selectedId) {
        if (!state.loading && selectedId != null && state.storedCards.none { it.id == selectedId }) {
            editorOpen = false
            editing = false
        }
    }
    if (collection != null && !editorOpen) {
        NoteCollectionScreen(collection!!, state, onBack = { collection = null }, onOpen = {
            selectedId = it.id; editing = false; editorOpen = true; viewModel.clearError()
        })
        return
    }
    if ((!editorOpen || !editing) && (!sliderOpen || editorOpen)) BoardContent(state, gridState = gridState, onAdd = {
        newNoteType = NoteType.NOTE
        selectedId = null
        editing = true
        editorOpen = true
        viewModel.clearError()
    }, onOpen = {
        viewModel.recordOpening(it.id, com.ontop.app.data.NoteInteractionType.NOTE_OPENED)
        selectedId = it.id
        editing = false
        editorOpen = true
        viewModel.clearError()
    }, onView = { viewModel.sliderSession.enter(filteredCards); sliderOpen = true }, onSettings = { settingsOpen = true },
        onSort = viewModel::setBoardSort, onSelectTag = viewModel::selectTag, onManageTags = { viewModel.clearError(); tagsOpen = true },
        onCollection = { collection = it }, onPercentages = { viewModel.clearError(); percentNoteId.value = it.id },
        onAddChecklist = { newNoteType = NoteType.CHECKLIST; selectedId = null; editing = true; editorOpen = true; viewModel.clearError() })

    if (tagsOpen) TagManagementDialog(state, viewModel::createTag, viewModel::renameTag, viewModel::deleteTag,
        onClose = { tagsOpen = false; viewModel.clearError() })

    if (sliderOpen && !editorOpen) NoteSliderScreen(filteredCards, viewModel.sliderSession,
        onSliderViewed = { viewModel.recordOpening(it, com.ontop.app.data.NoteInteractionType.SLIDER_VIEWED) },
        tags = state.tags, cardTags = state.cardTags,
        saving = state.saving, error = state.error,
        onSavePercentages = viewModel::savePercentages, onClearError = viewModel::clearError,
        onChecklistToggle = viewModel::toggleChecklistItem,
        onClose = { sliderOpen = false },
        onEdit = { selectedId = it.id; editing = true; editorOpen = true; viewModel.clearError() },
        onDetails = { selectedId = it.id; editing = false; editorOpen = true; viewModel.clearError() })

    if (editorOpen && selected != null && !editing) {
        CardDetailsDialog(selected, viewModel, lifecycle, state,
            onDismiss = { editorOpen = false },
            onEdit = { editing = true; viewModel.clearError() })
    }
    if (editorOpen && editing && (selectedId == null || selected != null)) {
        LaunchedEffect(selectedId, sharedDraft?.id) {
            viewModel.beginImageEdit(selected)
            sharedDraft?.let { draft ->
                if (draft.imageUri != null)
                    viewModel.prepareSharedImage(draft.id, android.net.Uri.parse(draft.imageUri), sharedImagePath)
            }
        }
        LaunchedEffect(sharedInlineImage, sharedDraft?.id) {
            if (sharedInlineImage?.first == sharedDraft?.id) sharedImagePath = sharedInlineImage?.second
        }
        key(sharedDraft?.id) {
        CardEditorRoute(
            card = selected,
            state = state,
            viewModel = viewModel,
            onDismiss = { viewModel.cancelImageEdit {
                editing = false; editorOpen = selected != null && !sliderOpen
                sharedDraft = null; sharedImagePath = null
            } },
            onSaved = { editorOpen = false; editing = false; sharedDraft = null; sharedImagePath = null },
            onDelete = { selected?.let { card -> viewModel.delete(card) { editorOpen = false; editing = false } } },
            sharedDraft = sharedDraft, sharedInlinePath = sharedImagePath,
            newNoteType = if (sharedDraft != null) NoteType.NOTE else newNoteType
        )
        }
    }
}

@Composable
internal fun BoardContent(state: BoardState, onAdd: () -> Unit, onOpen: (CardEntity) -> Unit,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(), onView: () -> Unit = {},
    onSettings: () -> Unit = {}, onSort: (BoardSort) -> Unit = {},
    onSelectTag: (Long?) -> Unit = {}, onManageTags: () -> Unit = {},
    onCollection: (com.ontop.app.data.CardStatus) -> Unit = {}, onPercentages: (CardEntity) -> Unit = {},
    onAddChecklist: () -> Unit = {}) {
    val displayedCards = remember(state.cards, state.sortMode, state.cardTags, state.selectedTagId) {
        sortedBoardCards(filterCardsByTag(state.cards, state.cardTags, state.selectedTagId), state.sortMode)
    }
    var previousSort by remember { mutableStateOf(state.sortMode) }
    var previousTag by remember { mutableStateOf(state.selectedTagId) }
    LaunchedEffect(state.sortMode, state.selectedTagId) {
        if (previousSort != state.sortMode || previousTag != state.selectedTagId) gridState.scrollToItem(0)
        previousSort = state.sortMode
        previousTag = state.selectedTagId
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.End)) {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = Forest,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.semantics { contentDescription = "Создать карточку" }
            ) { Icon(painterResource(R.drawable.ic_add_note), "Обычная заметка") }
            FloatingActionButton(onClick = onAddChecklist, containerColor = Forest, contentColor = Color.White,
                shape = RoundedCornerShape(20.dp), modifier = Modifier.semantics { contentDescription = "Создать чек-лист" }) {
                Icon(painterResource(R.drawable.ic_checklist), null)
            }
            }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            BoardHeader(onSettings, state.sortMode, onSort, !state.loading, onCollection, onView)
            TopicFilter(state, onSelectTag, onManageTags)
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            when {
                state.loading -> Text("Загружаем карточки…", Modifier.padding(20.dp))
                displayedCards.isEmpty() && state.selectedTagId != null -> Column(Modifier.padding(20.dp)) {
                    Text("В теме «${state.tags.firstOrNull { it.id == state.selectedTagId }?.name.orEmpty()}» пока нет заметок")
                    TextButton(onClick = { onSelectTag(null) }) { Text("Показать все заметки") }
                }
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
                        items(displayedCards, key = { it.id }, span = {
                            if (it.size == CardSize.WIDE) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane
                        }) { card -> ThoughtTile(card, onClick = { onOpen(card) },
                            onPercentages = { onPercentages(card) }, enabled = !state.saving) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtTile(card: CardEntity, onClick: () -> Unit, onPercentages: () -> Unit, enabled: Boolean) {
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
        Box {
            CardVisual(card, Modifier.testTag("tile-visual-${card.id}"), minHeight = minHeight)
            NoteStatusIndicator(card.importance, card.progress,
                Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("tile-status-${card.id}"),
                variant = StatusIndicatorSize.Compact, enabled = enabled, onClick = onPercentages)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BoardPreview() {
    OnTopTheme { BoardContent(BoardState(loading = false), onAdd = {}, onOpen = {}) }
}
