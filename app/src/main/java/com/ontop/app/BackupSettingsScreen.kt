package com.ontop.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ontop.app.backup.BackupState
import java.time.LocalDate

@Composable
internal fun BackupSettingsScreen(viewModel: BoardViewModel, state: BackupState, onClose: () -> Unit) {
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let(viewModel::createBackup)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::prepareBackup) }
    BackHandler { if (!state.busy && !state.recoveryRequired && state.ready == null) onClose() }
    Surface(Modifier.fillMaxSize().testTag("backup-settings"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose, enabled = !state.busy && !state.recoveryRequired) { Text("Назад") }
                Text("Настройки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Резервное копирование", style = MaterialTheme.typography.headlineSmall)
                Text("Сохраните заметки, комментарии, изображения и напоминания в переносимый файл .ontop.")
                Button(onClick = { try { create.launch("OnTop-${LocalDate.now()}.ontop") } catch (_: Exception) { viewModel.backup.pickerFailed() } },
                    enabled = !state.busy && !state.recoveryRequired, modifier = Modifier.fillMaxWidth()) { Text("Создать резервную копию") }
                OutlinedButton(onClick = { try { open.launch(arrayOf("*/*")) } catch (_: Exception) { viewModel.backup.pickerFailed() } }, enabled = !state.busy && !state.recoveryRequired,
                    modifier = Modifier.fillMaxWidth()) { Text("Восстановить из файла") }
                if (state.recoveryRequired) Button(onClick = viewModel::recoverBackup, enabled = !state.busy) {
                    Text("Завершить восстановление")
                }
                if (state.busy) CircularProgressIndicator()
                if (state.message.isNotBlank()) Text(state.message, modifier = Modifier.testTag("backup-message"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup-error")) }
            }
        }
    }
    if (state.ready != null) AlertDialog(onDismissRequest = viewModel::cancelBackupRestore,
        title = { Text("Заменить текущие данные?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${state.ready}\n\nВсе текущие заметки, комментарии и правила напоминаний будут заменены данными из файла. Для виджетов потребуется заново выбрать карточки.")
            }
        }, confirmButton = { TextButton(onClick = viewModel::restoreBackup, enabled = !state.busy) { Text("Заменить и восстановить") } },
        dismissButton = { TextButton(onClick = viewModel::cancelBackupRestore, enabled = !state.busy) { Text("Отмена") } })
}

@Composable
internal fun BackupProgressDialog(message: String) {
    AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Резервное копирование") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(); Text(message.ifBlank { "Подождите…" })
        } })
}
