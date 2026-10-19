package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Suppress("DEPRECATION") // Compatible with the project's existing Compose/Activity setup.
@Composable
internal fun AboutDialog(info: AppVersionInfo?, error: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("О приложении") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info != null) {
                    Text(info.name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("versionName: ${info.versionName}")
                    Text("versionCode: ${info.versionCode}")
                    Text("Идентификатор сборки: ${info.buildId}")
                    Text("Дата и время сборки: ${info.buildDate}")
                    Text("Тип сборки: ${info.typeLabel}")
                    TextButton(onClick = { clipboard.setText(AnnotatedString(info.clipboardText())) }) {
                        Text("Скопировать информацию о версии")
                    }
                } else if (error) {
                    Text("Не удалось загрузить информацию о версии.")
                    TextButton(onClick = onRetry) { Text("Повторить") }
                } else Text("Загрузка…")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
