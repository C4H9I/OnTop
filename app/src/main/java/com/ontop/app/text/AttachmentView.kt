package com.ontop.app.text

import android.content.ClipData
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.ontop.app.data.CardImageStore

@Composable
internal fun AttachmentView(block: NoteMedia, modifier: Modifier, onRemove: (() -> Unit)?, active: Boolean) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    if (block.isAudio || block.isLocalVideo) Column(modifier.fillMaxWidth().testTag("media-${block.id}")) {
        com.ontop.app.media.LocalMediaPlayer(block.id,
            android.net.Uri.fromFile(CardImageStore(context).resolve(requireNotNull(block.path))),
            block.name ?: if (block.isAudio) "Голосовая заметка" else "Видео", video = block.isLocalVideo,
            knownDuration = block.durationMillis, active = active,
            onRemove = if (onRemove != null) ({ confirm = true }) else null)
    } else Row(modifier.fillMaxWidth().testTag("media-${block.id}")) {
        TextButton(enabled = active, modifier = Modifier.weight(1f), onClick = {
            runCatching {
                val file = CardImageStore(context).resolve(requireNotNull(block.path))
                check(file.isFile)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.shares", file, block.name ?: "Файл")
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, block.mime ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("", uri) })
            }.onFailure { Toast.makeText(context, "Не удалось открыть файл. Проверьте наличие подходящего приложения.", Toast.LENGTH_LONG).show() }
        }) {
            Icon(androidx.compose.ui.res.painterResource(com.ontop.app.R.drawable.ic_attachment), contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(block.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Formatter.formatFileSize(context, block.bytes), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onRemove != null) TextButton(enabled = active, onClick = { confirm = true },
            modifier = Modifier.testTag("remove-media-${block.id}")) { Text("Удалить") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Удалить вложение?") },
        text = { Text(block.name.orEmpty()) },
        confirmButton = { TextButton(onClick = {
            com.ontop.app.media.MediaPlayback.get(context).stopIf(block.id)
            confirm = false; onRemove?.invoke()
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}
