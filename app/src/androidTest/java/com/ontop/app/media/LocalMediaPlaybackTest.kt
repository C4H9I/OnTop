package com.ontop.app.media

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.text.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalMediaPlaybackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun importedMp3AndLocalVideoUseOneEngineSeekPauseAndStopOnRemoval() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "shared-notes/player-${UUID.randomUUID()}").apply { mkdirs() }
        val store = CardImageStore(context)
        val blocks = listOf("sample.mp3", "sample.mp4").map { name ->
            val file = File(dir, name)
            file.outputStream().use { output -> repeat(if (name.endsWith(".mp3")) 10 else 1) {
                instrumentation.context.assets.open("media/$name").use { it.copyTo(output) }
            } }
            AttachmentStore(context).import(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.shares", file))
        }
        val playback = MediaPlayback.get(context)
        var active by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var removed by mutableStateOf(false)
        try {
            compose.setContent { OnTopTheme { Column {
                if (visible) {
                    NoteMediaView(blocks[0].copy(kind = MediaKind.FILE), active = active)
                    if (!removed) NoteMediaView(blocks[1], active = active, onRemove = { removed = true })
                }
            } } }
            val audio = blocks[0].id; val video = blocks[1].id
            compose.onNodeWithTag("media-play-$audio").performClick()
            compose.waitUntil(20000) { playback.duration > 0 && playback.playing }
            lateinit var engine: androidx.media3.exoplayer.ExoPlayer
            compose.runOnIdle { engine = playback.engine!!; engine.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE; playback.pauseIf(audio) }
            compose.onNodeWithTag("media-seek-$audio").performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
            compose.runOnIdle { assertTrue(kotlin.math.abs(playback.duration / 2 - playback.position) <= 150) }
            compose.onNodeWithTag("media-play-$video").performClick()
            compose.waitUntil(20000) { playback.key == video && playback.duration > 0 && playback.playing }
            compose.runOnIdle { assertSame(engine, playback.engine); assertNull(playback.error) }
            compose.onNodeWithTag("remove-media-$video").performClick()
            compose.onAllNodesWithText("Удалить").onLast().performClick()
            compose.runOnIdle { assertTrue(removed); assertNull(playback.key); assertFalse(playback.playing) }
            compose.onNodeWithTag("media-play-$audio").performClick()
            compose.waitUntil(20000) { playback.playing }
            compose.runOnIdle { active = false }
            compose.waitUntil(5000) { playback.key == null }
            compose.runOnIdle { visible = false }
            compose.waitUntil(5000) { playback.engine == null }
        } finally {
            compose.runOnIdle { visible = false }
            blocks.forEach { store.delete(it.path) }; dir.deleteRecursively()
        }
    }
}
