package com.ontop.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** One row of insertion actions; buttons never take focus from the rich text field. */
@Composable
internal fun EditorInsertToolbar(enabled: Boolean, onFile: (() -> Unit)?, onPhoto: (() -> Unit)?,
    onVideo: () -> Unit, onAudio: () -> Unit, onDraw: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)
        .testTag("editor-insert-toolbar"), horizontalArrangement = Arrangement.SpaceEvenly) {
        @Composable fun Action(icon: Int, label: String, action: () -> Unit, tag: String) {
            IconButton(onClick = action, enabled = enabled,
                modifier = Modifier.size(48.dp).focusProperties { canFocus = false }.testTag(tag)) {
                Icon(painterResource(icon), contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (onFile != null) Action(R.drawable.ic_attachment, "Прикрепить файл", onFile, "editor-insert-file")
        if (onPhoto != null) Action(R.drawable.ic_cover, "Добавить фото", onPhoto, "editor-insert-photo")
        Action(R.drawable.ic_video, "Добавить видео", onVideo, "editor-insert-video")
        Action(R.drawable.ic_audio, "Добавить аудио", onAudio, "editor-insert-audio")
        Action(R.drawable.ic_draw, "Нарисовать", onDraw, "editor-insert-draw")
        Action(R.drawable.ic_sliders, "Настройки заметки", onSettings, "editor-settings-compact")
    }
}
