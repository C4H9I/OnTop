package com.ontop.app.text

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ontop.app.LocalCardImage

internal val LocalNoteMediaActive = compositionLocalOf { true }

@Composable
internal fun NoteMediaView(block: NoteMedia, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null,
    active: Boolean = true, onConvertToLink: (() -> Unit)? = null, onEditImage: (() -> Unit)? = null) {
    val mediaActive = active && LocalNoteMediaActive.current
    var fullImage by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(mediaActive) { if (!mediaActive) { playing = false; fullImage = false; imageMenu = false } }
    if (block.isAttachment) { AttachmentView(block, modifier, onRemove, mediaActive); return }
    if (block.kind == MediaKind.YOUTUBE) {
        YouTubePreview(block.video!!, modifier.fillMaxWidth().testTag("media-${block.id}"),
            onPlay = { if (mediaActive) playing = true }, onRemove = onRemove, onConvertToLink = onConvertToLink)
    } else Box(modifier.fillMaxWidth().testTag("media-${block.id}")) {
        LocalCardImage(block.path!!, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .testTag("inline-image-${block.id}").combinedClickable(
                role = Role.Button, onClickLabel = if (onEditImage != null) "Действия с изображением" else "Открыть изображение",
                onClick = { if (mediaActive) { if (onEditImage != null) imageMenu = true else fullImage = true } },
                onLongClickLabel = if (onRemove != null) "Действия с изображением" else null,
                onLongClick = if (onRemove != null) ({ if (mediaActive) imageMenu = true }) else null),
            contentScale = ContentScale.Fit, maxSide = 1000, adaptHeight = true)
        if (onRemove != null) DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
            if (onEditImage != null) DropdownMenuItem(text = { Text("Редактировать изображение") },
                onClick = { imageMenu = false; onEditImage() })
            DropdownMenuItem(text = { Text("Открыть изображение") }, onClick = { imageMenu = false; fullImage = true })
            DropdownMenuItem(text = { Text("Удалить изображение из текста") },
                modifier = Modifier.testTag("remove-media-${block.id}"),
                onClick = { imageMenu = false; onRemove() })
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
