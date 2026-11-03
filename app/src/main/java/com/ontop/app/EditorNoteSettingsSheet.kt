package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class EditorSettingsSection { REMINDERS, DISPLAY, TOPICS, OTHER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorNoteSettingsSheet(remindersReady: Boolean, onDismiss: () -> Unit,
    onSelect: (EditorSettingsSection) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("editor-settings-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Настройки заметки", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            @Composable fun Section(label: String, section: EditorSettingsSection, enabled: Boolean = true) {
                TextButton(onClick = { onSelect(section) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor-setting-${section.name}"),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Section("Напоминания", EditorSettingsSection.REMINDERS, remindersReady)
            Section("Отображение", EditorSettingsSection.DISPLAY)
            Section("Темы", EditorSettingsSection.TOPICS)
            Section("Другие настройки", EditorSettingsSection.OTHER)
        }
    }
}
