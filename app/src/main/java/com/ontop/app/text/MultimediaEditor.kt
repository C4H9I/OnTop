package com.ontop.app.text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class MediaInsertPoint(val key: String, val cursor: Int) : java.io.Serializable
internal data class VideoPaste(val key: String, val start: Int, val end: Int, val text: String,
    val video: YouTubeLink) : java.io.Serializable

@Composable
internal fun MultimediaEditor(document: RichDocument, onChange: (RichDocument) -> Unit, enabled: Boolean,
    root: NativeRichController, activeKey: String, onControllerReady: (NativeRichController) -> Unit,
    onActive: (String, NativeRichController, Boolean) -> Unit,
    focusAfter: MediaInsertPoint?, onFocusHandled: () -> Unit,
    onMoveCursor: (MediaInsertPoint) -> Unit, onPaste: (VideoPaste) -> Unit,
    minimumLines: Int = 12, placeholder: String = "О чём хочется помнить?") {
    val current by rememberUpdatedState(document)
    val change by rememberUpdatedState(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        document.pieces().forEach { piece -> key(piece.key) {
            val controller = if (piece.index == 0) root else remember { NativeRichController() }
            val bringIntoView = remember { BringIntoViewRequester() }
            SideEffect { if (piece.key == activeKey) onControllerReady(controller) }
            controller.onYouTubePaste = { text, start, end ->
                YouTubeLink.parse(text)?.let { onPaste(VideoPaste(piece.key, start, end, text, it)) }
            }
            NativeRichEditor(document.fragment(piece), { fragment -> change(current.replacePiece(piece.key, fragment)) }, enabled,
                controller, { focused -> onActive(piece.key, controller, focused) },
                modifier = Modifier.bringIntoViewRequester(bringIntoView),
                minimumLines = if (document.media.isEmpty()) minimumLines else 1,
                placeholder = if (document.media.isEmpty()) placeholder else "Продолжить текст…",
                editorTag = if (piece.index == 0) "editor-description" else "editor-text-${piece.key}")
            youtubeLinks(document.fragment(piece).text).forEach { link ->
                TextButton(enabled = enabled, onClick = { change(current.linkToVideo(piece.key, link)) },
                    modifier = Modifier.testTag("link-to-video-${piece.key}-${link.start}")) {
                    Column {
                        Text("Преобразовать в видео")
                        Text(link.video.source, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            LaunchedEffect(focusAfter, piece.key) {
                if (focusAfter?.key == piece.key) {
                    controller.focus()
                    controller.select(focusAfter.cursor, focusAfter.cursor)
                    // Wait for the split fields and keyboard insets before scrolling to the cursor.
                    withFrameNanos { }
                    bringIntoView.bringIntoView()
                    onFocusHandled()
                }
            }
            document.media.getOrNull(piece.index)?.let { block ->
                Column(Modifier.testTag("editor-block-${block.id}")) {
                    NoteMediaView(block, onRemove = if (enabled) ({
                        val index = current.media.indexOfFirst { it.id == block.id }
                        if (index >= 0) {
                            val before = current.pieces()[index]
                            val position = current.fragment(before).text.length
                            change(current.removeMedia(block.id))
                            onMoveCursor(MediaInsertPoint(before.key, position))
                        }
                    }) else null, onConvertToLink = if (enabled && block.kind == MediaKind.YOUTUBE) ({
                        change(current.videoToLink(block.id))
                        onMoveCursor(MediaInsertPoint(piece.key, Int.MAX_VALUE))
                    }) else null)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = enabled, onClick = { onMoveCursor(MediaInsertPoint(piece.key, Int.MAX_VALUE)) },
                            modifier = Modifier.testTag("before-media-${block.id}")) { Text("Текст до") }
                        TextButton(enabled = enabled, onClick = { onMoveCursor(MediaInsertPoint(block.id, 0)) },
                            modifier = Modifier.testTag("after-media-${block.id}")) { Text("Текст после") }
                    }
                }
            }
        } }
    }
}

@Composable
internal fun InsertVideoDialog(onInsert: (YouTubeLink) -> Unit, onClose: () -> Unit) {
    var url by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val video = YouTubeLink.parse(url)
    AlertDialog(onDismissRequest = onClose, title = { Text("Вставить YouTube-видео") }, text = {
        Column {
            OutlinedTextField(url, { url = it }, label = { Text("Ссылка YouTube") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = url.isNotBlank() && video == null)
            if (url.isNotBlank() && video == null) Text("Проверьте ссылку и идентификатор ролика", color = MaterialTheme.colorScheme.error)
            if (video != null && video.seconds > 0) Text("Начало: ${video.seconds / 60}:${(video.seconds % 60).toString().padStart(2, '0')}")
        }
    }, confirmButton = { TextButton(onClick = { video?.let(onInsert) }, enabled = video != null) { Text("Вставить видео") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } })
}
