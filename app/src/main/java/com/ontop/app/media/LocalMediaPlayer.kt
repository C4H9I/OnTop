package com.ontop.app.media

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.ontop.app.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun LocalMediaPlayer(owner: String, uri: Uri, name: String, video: Boolean = false,
    knownDuration: Long? = null, active: Boolean = true, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val playback = remember(context) { MediaPlayback.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, owner, lifecycle) {
        playback.attach()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playback.pauseIf(owner)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); playback.stopIf(owner); playback.detach() }
    }
    LaunchedEffect(active) { if (!active) playback.stopIf(owner) }
    val selected = playback.key == owner
    val duration = if (selected && playback.duration > 0) playback.duration else knownDuration ?: 0
    val position = if (selected) playback.position.coerceAtMost(duration.coerceAtLeast(0)) else 0
    Column(Modifier.fillMaxWidth().testTag("local-player-$owner")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = active, onClick = { playback.toggle(owner, uri) }, modifier = Modifier.testTag("media-play-$owner")) {
                Icon(painterResource(if (selected && playback.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                    contentDescription = if (selected && playback.playing) "Пауза" else "Воспроизвести")
            }
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("${mediaTime(position)} / ${mediaTime(duration)}", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("media-time-$owner"))
            }
            if (onRemove != null) TextButton(onClick = onRemove, enabled = active,
                modifier = Modifier.testTag("remove-media-$owner")) { Text("Удалить") }
        }
        if (video) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(enabled = active) { playback.toggle(owner, uri) },
            contentAlignment = Alignment.Center) {
            if (selected) AndroidView(factory = { PlayerView(it).apply { useController = false } },
                modifier = Modifier.fillMaxSize(), update = { it.player = playback.engine }, onRelease = { it.player = null })
            else Icon(painterResource(R.drawable.ic_video), "Воспроизвести видео", Modifier.size(40.dp))
        }
        Slider(value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { playback.seek(owner, (it * duration).toLong()) },
            enabled = active && selected && duration > 0,
            modifier = Modifier.fillMaxWidth().height(30.dp).testTag("media-seek-$owner"))
        if (selected) playback.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
