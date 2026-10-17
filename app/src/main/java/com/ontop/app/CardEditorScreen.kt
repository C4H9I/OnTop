package com.ontop.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardSize

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
    modifier: Modifier = Modifier
) {
    // Capture the initial values once: a Room emission must not reset an active draft.
    val initialTitle = rememberSaveable(card?.id) { card?.title.orEmpty() }
    val initialDescription = rememberSaveable(card?.id) { card?.description.orEmpty() }
    val initialSize = rememberSaveable(card?.id) { card?.size ?: CardSize.NORMAL }
    val initialImage by rememberSaveable(card?.id) { mutableStateOf(card?.imagePath) }
    var title by rememberSaveable(card?.id) { mutableStateOf(initialTitle) }
    var description by rememberSaveable(card?.id) { mutableStateOf(initialDescription) }
    var size by rememberSaveable(card?.id) { mutableStateOf(initialSize) }
    var confirmDiscard by rememberSaveable(card?.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(card?.id) { mutableStateOf(false) }
    var focusRequested by rememberSaveable(card?.id) { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val formScroll = rememberScrollState()
    val formDragged by formScroll.interactionSource.collectIsDraggedAsState()
    val dismissKeyboard = { focus.clearFocus(); keyboard?.hide(); Unit }
    // Gboard's floating hardware-keyboard toolbar belongs to the IME, not this screen.
    // Dismiss it when leaving text entry; never change global keyboard preferences.
    LaunchedEffect(formDragged) { if (formDragged) dismissKeyboard() }
    val changed = title != initialTitle || description != initialDescription ||
        size != initialSize || imagePath != initialImage
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

    Surface(modifier.fillMaxSize().testTag("card-editor"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад")
                }
                Text(if (card == null) "Новая карточка" else "Карточка",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(formScroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp).testTag("editor-fields"),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, enabled = !saving,
                    label = { Text("Короткая фраза") },
                    supportingText = { Text("Обязательно") },
                    minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("editor-title")
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, enabled = !saving,
                    label = { Text("Подробное описание") }, minLines = 3, maxLines = 8,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus).testTag("editor-description")
                )
                Text("Изображение", style = MaterialTheme.typography.titleMedium)
                if (imagePath != null) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        LocalCardImage(imagePath, Modifier.fillMaxWidth().height(220.dp).testTag("editor-image"),
                            contentScale = ContentScale.Fit)
                    }
                }
                OutlinedButton(onClick = {
                    keyboard?.hide()
                    try { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { onPickerFailed() }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
                    Text(if (imagePath == null) "Выбрать изображение" else "Заменить изображение")
                }
                if (imagePath != null) {
                    TextButton(onClick = onRemoveImage, enabled = !saving) { Text("Удалить изображение") }
                }
                CardSizeSelector(size = size, enabled = !saving, onSelect = {
                    dismissKeyboard()
                    size = it
                })
                if (card != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text("Удалить карточку", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 88.dp)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Button(onClick = { onSave(title, description, size) },
                enabled = !saving && title.isNotBlank(), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 52.dp).testTag("editor-save")) {
                Text(if (saving) "Сохраняем…" else "Сохранить")
            }
        }
    }
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
            title = { Text("Удалить карточку?") },
            text = { Text("Карточка «${card.title}» будет удалена. Восстановить её не получится.") },
            confirmButton = { TextButton(onClick = onDelete, enabled = !saving) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !saving) { Text("Отмена") } })
    }
}
