package com.ontop.app.text

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ontop.app.LocalCardImage

internal val LocalNoteMediaActive = compositionLocalOf { true }

@Composable
internal fun NoteMediaView(block: NoteMedia, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null,
    active: Boolean = true) {
    val mediaActive = active && LocalNoteMediaActive.current
    var fullImage by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(mediaActive) { if (!mediaActive) { playing = false; fullImage = false } }
    if (block.kind == MediaKind.YOUTUBE) {
        YouTubePreview(block.video!!, modifier.fillMaxWidth().testTag("media-${block.id}"),
            onPlay = { if (mediaActive) playing = true }, onRemove = onRemove)
    } else Surface(modifier.fillMaxWidth().testTag("media-${block.id}"), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            if (block.kind == MediaKind.IMAGE) {
                LocalCardImage(block.path!!, Modifier.fillMaxWidth().clickable { fullImage = true },
                    contentScale = ContentScale.Fit, maxSide = 1000, adaptHeight = true)
                TextButton(onClick = { fullImage = true }) { Text("Открыть изображение") }
            }
            if (onRemove != null) TextButton(onClick = onRemove, modifier = Modifier.testTag("remove-media-${block.id}")) {
                Text("Удалить изображение из текста")
            }
        }
    }
    if (fullImage && mediaActive) Dialog(onDismissRequest = { fullImage = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("full-note-image"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                TextButton(onClick = { fullImage = false }) { Text("Закрыть изображение") }
                LocalCardImage(block.path!!, Modifier.fillMaxWidth().weight(1f), ContentScale.Fit, maxSide = 2000)
            }
        }
    }
    if (playing && mediaActive) YouTubePlayerDialog(block.video!!) { playing = false }
}

internal fun java.io.InputStream.readBytesLimited(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer); if (read < 0) break
        require(output.size() + read <= maximum); output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

@Composable
internal fun OpenVideoLink(video: YouTubeLink) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = {
            failed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.watchUrl))) }.isFailure
        }) { Text("Открыть в YouTube или браузере") }
        if (failed) Text("Нет приложения для открытия ссылки. ${video.watchUrl}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MultimediaNoteView(document: RichDocument, active: Boolean = true) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        document.pieces().forEach { piece ->
            val fragment = document.fragment(piece)
            if (fragment.text.isNotEmpty()) Text(fragment.annotated(MaterialTheme.typography.bodyLarge.fontSize, dark),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("card-description-${piece.key}"))
            document.media.getOrNull(piece.index)?.let { block -> key(block.id) { NoteMediaView(block, active = active) } }
        }
    }
}
