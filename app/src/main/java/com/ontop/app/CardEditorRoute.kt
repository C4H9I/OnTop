package com.ontop.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardSize
import com.ontop.app.reminders.ReminderDraft
import com.ontop.app.reminders.draft
import kotlinx.coroutines.CancellationException

/** The whole reminder is a saveable editor draft; opening its settings never writes to Room. */
@Composable
internal fun CardEditorRoute(card: CardEntity?, state: BoardState, viewModel: BoardViewModel,
    onDismiss: () -> Unit, onSaved: () -> Unit, onDelete: () -> Unit) {
    var initialReminder by rememberSaveable(card?.id) { mutableStateOf<ReminderDraft?>(null) }
    var reminder by rememberSaveable(card?.id) { mutableStateOf<ReminderDraft?>(null) }
    var loaded by rememberSaveable(card?.id) { mutableStateOf(card == null) }
    var loadError by remember(card?.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var reminderOpen by rememberSaveable(card?.id) { mutableStateOf(false) }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Capture the document being saved while the system permission dialog is open.
    var pendingTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDescription by rememberSaveable { mutableStateOf("") }
    var pendingSize by rememberSaveable { mutableStateOf(CardSize.NORMAL) }
    var pendingFormatting by rememberSaveable { mutableStateOf<String?>(null) }
    val changed = reminder != initialReminder
    LaunchedEffect(card?.id, retry) {
        if (!loaded && card != null) {
            loadError = null
            try {
                val actual = viewModel.loadReminder(card.id)?.draft()
                initialReminder = actual
                reminder = actual
                loaded = true
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { loadError = "Не удалось загрузить напоминание. Повторите попытку перед сохранением." }
        }
    }
    fun save(title: String, description: String, size: CardSize, formatting: String?) {
        viewModel.saveNote(card, title, description, size, reminder, changed, onSaved, formatting)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestingPermission = false
        pendingTitle?.let { save(it, pendingDescription, pendingSize, pendingFormatting) }
        pendingTitle = null
    }
    CardEditorScreen(card, state.saving || requestingPermission, state.error, state.draftImagePath,
        onImageSelected = viewModel::importImage, onRemoveImage = viewModel::removeDraftImage,
        onInlineImport = viewModel::importInlineImage, onInlineDraftPaths = viewModel::trackInlineDraft,
        onPickerFailed = viewModel::imagePickerFailed, onDismiss = onDismiss,
        onSave = { _, _, _ -> },
        onSaveFormatted = { title, description, size, formatting ->
            if (changed && reminder?.enabled == true && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingTitle = title; pendingDescription = description; pendingSize = size
                pendingFormatting = formatting
                requestingPermission = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else save(title, description, size, formatting)
        }, onDelete = onDelete, reminderChanged = changed, remindersReady = loaded,
        onReminders = { reminderOpen = true }, reminderLoadError = loadError, onRetryReminders = { retry++ })
    if (reminderOpen) {
        ReminderDraftDialog(reminder, onApply = { reminder = it }, onDismiss = { reminderOpen = false })
    }
}
