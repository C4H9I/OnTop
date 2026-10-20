package com.ontop.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardSize
import com.ontop.app.data.TagEntity
import com.ontop.app.text.*

/** One editor route for both creation and updates; persistence stays in BoardViewModel. */
@Composable
internal fun CardEditorScreen(
    card: CardEntity?,
    saving: Boolean,
    error: String?,
    imagePath: String?,
    onImageSelected: (Uri) -> Unit,
    onRemoveImage: () -> Unit,
    onPickerFailed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, CardSize) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    reminderChanged: Boolean = false,
    remindersReady: Boolean = true,
    onReminders: () -> Unit = {},
    reminderLoadError: String? = null,
    onRetryReminders: () -> Unit = {},
    onSaveFormatted: ((String, String, CardSize, String?) -> Unit)? = null,
    onInlineImport: ((Uri, (String) -> Unit) -> Unit)? = null,
    onInlineDraftPaths: (Set<String>) -> Unit = {},
    onSaveWithImportance: ((String, String, CardSize, String?, Int) -> Unit)? = null,
    availableTags: List<TagEntity> = emptyList(), initialTagIds: List<Long> = emptyList(),
    onSaveTagged: ((String, String, CardSize, String?, Int, Int?, List<Long>, List<String>) -> Unit)? = null,
    initialSharedTitle: String? = null, initialSharedText: String? = null, sharedInlinePath: String? = null
) {
    // Capture the initial values once: a Room emission must not reset an active draft.
    val initialTitle = rememberSaveable(card?.id) { card?.title ?: initialSharedTitle.orEmpty() }
    val initialDescription = rememberSaveable(card?.id) { card?.description ?: initialSharedText.orEmpty() }
    val initialDocument = rememberSaveable(card?.id) {
        RichTextCodec.decode(initialDescription, card?.formatting).let {
            if (card == null && initialSharedText != null) it.convertYouTubeLinks() else it
        }
    }
    val initialSize = rememberSaveable(card?.id) { card?.size ?: CardSize.NORMAL }
    val initialImage by rememberSaveable(card?.id) { mutableStateOf(card?.imagePath) }
    var title by rememberSaveable(card?.id) { mutableStateOf(initialTitle) }
    var document by rememberSaveable(card?.id) { mutableStateOf(initialDocument) }
    var sharedImageInserted by rememberSaveable(card?.id) { mutableStateOf(false) }
    LaunchedEffect(sharedInlinePath) {
        if (sharedInlinePath != null && !sharedImageInserted) {
            document = document.copy(media = document.media + NoteMedia(offset = document.text.length,
                kind = MediaKind.IMAGE, path = sharedInlinePath)).normalized()
            onInlineDraftPaths(setOf(sharedInlinePath))
            sharedImageInserted = true
        }
    }
    val description = document.text
    var size by rememberSaveable(card?.id) { mutableStateOf(initialSize) }
    val initialPercentages = rememberSaveable(card?.id) { NotePercentages(card?.importance ?: 50, card?.progress) }
    var percentages by rememberSaveable(card?.id) { mutableStateOf(initialPercentages) }
    val importance = percentages.importance
    val progress = percentages.progress
    var percentagesOpen by rememberSaveable(card?.id) { mutableStateOf(false) }
    val topics = rememberEditorTopics(card?.id, initialTagIds)
    var confirmDiscard by rememberSaveable(card?.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(card?.id) { mutableStateOf(false) }
    var displayOpen by rememberSaveable(card?.id) { mutableStateOf(false) }
    var otherOpen by rememberSaveable(card?.id) { mutableStateOf(false) }
    var focusRequested by rememberSaveable(card?.id) { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val rootController = remember(card?.id) { NativeRichController() }
    var bodyController by remember(card?.id) { mutableStateOf(rootController) }
    var activePiece by rememberSaveable(card?.id) { mutableStateOf("body") }
    var pendingInsert by rememberSaveable(card?.id) { mutableStateOf(MediaInsertPoint("body", 0)) }
    var focusAfter by rememberSaveable(card?.id) { mutableStateOf<MediaInsertPoint?>(null) }
    var videoDialog by rememberSaveable(card?.id) { mutableStateOf(false) }
    var pastedVideo by rememberSaveable(card?.id) { mutableStateOf<VideoPaste?>(null) }
    var bodyFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val formScroll = rememberScrollState()
    val dismissKeyboard = { focus.clearFocus(); bodyController.view?.clearFocus(); keyboard?.hide(); Unit }
    val changed = title != initialTitle || document != initialDocument ||
        size != initialSize || imagePath != initialImage || reminderChanged || percentages != initialPercentages || topics.changed
    val leave = {
        if (!saving) {
            if (changed) confirmDiscard = true else onDismiss()
        }
    }
    BackHandler { leave() }
    LaunchedEffect(card?.id) {
        if (card == null && !focusRequested) {
            titleFocus.requestFocus()
            keyboard?.show()
            focusRequested = true
        }
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onImageSelected(uri)
    }
    val chooseImage = {
        dismissKeyboard()
        try { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { onPickerFailed() }
    }
    LaunchedEffect(document.imagePaths()) { onInlineDraftPaths(document.imagePaths()) }
    fun insertBlock(block: NoteMedia) {
        document = document.insertMedia(pendingInsert.key, pendingInsert.cursor, block)
        focusAfter = MediaInsertPoint(block.id, 0)
    }
    val inlinePicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onInlineImport?.invoke(uri) { path ->
            insertBlock(NoteMedia(offset = 0, kind = MediaKind.IMAGE, path = path))
        }
    }
    fun captureInsertion() { pendingInsert = MediaInsertPoint(activePiece, minOf(bodyController.start, bodyController.end)) }
    val chooseInlineImage = {
        captureInsertion()
        try { inlinePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { onPickerFailed() }
    }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val saveDocument = {
        if (onSaveTagged != null) onSaveTagged(title, description, size, RichTextCodec.encode(document), importance, progress, topics.ids.toList(), topics.names.toList())
        else if (onSaveWithImportance != null) onSaveWithImportance(title, description, size, RichTextCodec.encode(document), importance)
        else if (onSaveFormatted != null) onSaveFormatted(title, description, size, RichTextCodec.encode(document))
        else onSave(title, description, size)
    }

    Surface(modifier.fillMaxSize().testTag("card-editor"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад")
                }
                Text(if (card == null) "Новая заметка" else "Редактирование",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = chooseImage, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_cover), contentDescription = "Выбрать обложку")
                }
                IconButton(onClick = saveDocument,
                    enabled = !saving && remindersReady && title.isNotBlank(), modifier = Modifier.testTag("editor-save")) {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_check), contentDescription = "Сохранить заметку")
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(formScroll)
                    .padding(horizontal = 24.dp, vertical = 20.dp).testTag("editor-fields"),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                BasicTextField(
                    value = title, onValueChange = { title = it }, enabled = !saving,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { field -> Box {
                        if (title.isEmpty()) Text("Заголовок", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                        field()
                    } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { bodyController.focus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("editor-title")
                        .semantics { contentDescription = "Заголовок заметки, обязательное поле" }
                )
                MultimediaEditor(document, onChange = { document = it }, enabled = !saving,
                    root = rootController, activeKey = activePiece, onControllerReady = { bodyController = it },
                    onActive = { key, controller, focused ->
                        if (focused) { activePiece = key; bodyController = controller; bodyFocused = true }
                        else if (bodyController === controller) bodyFocused = false
                    }, focusAfter = focusAfter, onFocusHandled = { focusAfter = null },
                    onMoveCursor = { focusAfter = it }, onPaste = { pastedVideo = it })
            }
            if (reminderLoadError != null) {
                Text(reminderLoadError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = onRetryReminders, enabled = !saving) { Text("Повторить загрузку напоминания") }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 88.dp)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (bodyFocused || document.media.isNotEmpty()) RichFormatToolbar(
                document.pieces().firstOrNull { it.key == activePiece }?.let(document::fragment) ?: RichDocument(),
                bodyController, enabled = !saving,
                onInsertImage = if (onInlineImport != null) chooseInlineImage else null,
                onInsertVideo = { captureInsertion(); videoDialog = true })
            pastedVideo?.let { proposal ->
                FlowRow(Modifier.heightIn(max = 128.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        val piece = document.pieces().firstOrNull { it.key == proposal.key }
                        if (piece != null) {
                            val fragment = document.fragment(piece)
                            if (proposal.end <= fragment.text.length && fragment.text.substring(proposal.start, proposal.end) == proposal.text) {
                                document = document.linkToVideo(proposal.key,
                                    VideoLinkOccurrence(proposal.start, proposal.end, proposal.video))
                            }
                        }
                        pastedVideo = null
                    }) { Text("Преобразовать ссылку в видео") }
                    TextButton(onClick = { pastedVideo = null }) { Text("Оставить ссылкой") }
                }
            }
            if (imeOpen) {
                TextButton(onClick = dismissKeyboard, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("editor-settings-compact")) { Text("Настройки заметки") }
            } else {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("editor-settings"),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { dismissKeyboard(); onReminders() }, enabled = !saving && remindersReady) { Text("Напоминания") }
                    TextButton(onClick = { dismissKeyboard(); displayOpen = true }, enabled = !saving) { Text("Отображение") }
                    TextButton(onClick = { dismissKeyboard(); topics.open = true }, enabled = !saving) { Text("Темы") }
                    TextButton(onClick = { dismissKeyboard(); otherOpen = true }, enabled = !saving) { Text("Другие настройки") }
                }
            }
        }
    }
    if (videoDialog) InsertVideoDialog(onInsert = { video ->
        insertBlock(NoteMedia(offset = 0, kind = MediaKind.YOUTUBE, video = video)); videoDialog = false
    }, onClose = { videoDialog = false })
    if (displayOpen) EditorDisplayDialog(imagePath, size, saving, chooseImage, onRemoveImage,
        onSize = { dismissKeyboard(); size = it }, onClose = { displayOpen = false })
    if (otherOpen) EditorOtherDialog(card != null, importance, progress, saving,
        onImportance = { percentages = percentages.copy(importance = it) }, onPercentages = { percentagesOpen = true },
        onDelete = { otherOpen = false; confirmDelete = true }, onClose = { otherOpen = false })
    if (topics.open) TagPickerDialog(availableTags, topics.ids, topics.names, !saving,
        onChange = { ids, names -> topics.ids = ids; topics.names = names }, onClose = { topics.open = false })
    if (percentagesOpen) NotePercentagesDialog(card?.id ?: 0L, importance, progress,
        onDismiss = { percentagesOpen = false },
        onSave = { newImportance, newProgress -> percentages = NotePercentages(newImportance, newProgress); percentagesOpen = false })
    if (confirmDiscard) {
        AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Отменить изменения?") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }, enabled = !saving) { Text("Не сохранять") }
            }, dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить редактирование") }
            })
    }
    if (confirmDelete && card != null) {
        AlertDialog(onDismissRequest = { if (!saving) confirmDelete = false },
            title = { Text("Переместить в корзину?") },
            text = { Text("Заметку «${card.title}» можно будет восстановить из корзины.") },
            confirmButton = { TextButton(onClick = onDelete, enabled = !saving) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !saving) { Text("Отмена") } })
    }
}
