package com.ontop.app.media

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.gallery.CameraCaptureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule

class VoiceRecorderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun microphonePauseResumeInterruptionProducesAacAndCancelRemovesTemporaryFile() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        compose.setContent { androidx.compose.material3.Text("Recorder test") }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val captures = CameraCaptureStore(context)
        val name = captures.prepare("m4a")
        lateinit var recorder: VoiceRecorder
        var initialized = false
        var attached = false
        lateinit var playback: MediaPlayback
        var importedPath: String? = null
        val backupDir = java.io.File(context.cacheDir, "voice-backup-${System.nanoTime()}").apply { mkdirs() }
        try {
            instrumentation.runOnMainSync { recorder = VoiceRecorder(context, captures.file(name)); initialized = true; recorder.start() }
            assertEquals(recorder.message, RecordingState.RECORDING, recorder.state)
            delay(1400)
            instrumentation.runOnMainSync { recorder.pauseOrResume() }
            assertEquals(RecordingState.PAUSED, recorder.state)
            val paused = recorder.elapsed
            delay(400)
            assertEquals(paused, recorder.elapsed)
            instrumentation.runOnMainSync { recorder.pauseOrResume() }
            delay(800)
            instrumentation.runOnMainSync { recorder.interrupt("Запись остановлена при уходе в фон.") }
            assertEquals(recorder.message, RecordingState.PREVIEW, recorder.state)
            assertTrue(captures.file(name).length() > 0)
            val reader = android.media.MediaMetadataRetriever()
            try {
                reader.setDataSource(captures.file(name).absolutePath)
                assertTrue(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 1000)
                assertTrue(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)!!.startsWith("audio/"))
            } finally { reader.release() }
            instrumentation.runOnMainSync {
                playback = MediaPlayback.get(context); playback.attach(); attached = true
                playback.toggle("recording-preview-test", android.net.Uri.fromFile(captures.file(name)))
            }
            kotlinx.coroutines.withTimeout(20000) { while (!playback.playing || playback.duration <= 0) delay(100) }
            instrumentation.runOnMainSync { playback.stop(); playback.detach(); attached = false }
            val block = com.ontop.app.data.AttachmentStore(context).import(captures.uri(name))
            importedPath = block.path
            assertEquals(com.ontop.app.text.MediaKind.AUDIO, block.kind)
            val document = com.ontop.app.text.RichDocument(media = listOf(block))
            val card = com.ontop.app.data.CardEntity(id = 1, title = "Voice", createdAt = 1, updatedAt = 1, sortOrder = 0,
                formatting = com.ontop.app.text.RichTextCodec.encode(document))
            val archive = java.io.File(backupDir, "voice.ontop")
            val images = com.ontop.app.data.CardImageStore(context)
            archive.outputStream().use { output -> com.ontop.app.backup.BackupFormat.write(
                com.ontop.app.backup.BackupData(listOf(card), emptyList(), emptyList()), images::resolve, output) }
            val restored = com.ontop.app.backup.BackupFormat.read(archive, java.io.File(backupDir, "restored"))
            assertEquals(card, restored.cards.single())
            assertArrayEquals(captures.file(name).readBytes(), java.io.File(backupDir, "restored/${block.path}").readBytes())
            instrumentation.runOnMainSync { recorder.close() }
            assertFalse(captures.file(name).exists())
        } finally { instrumentation.runOnMainSync {
            if (attached) { playback.stop(); playback.detach() }
            if (initialized) recorder.close()
        }; captures.delete(name); importedPath?.let { com.ontop.app.data.CardImageStore(context).delete(it) }; backupDir.deleteRecursively() }
    }
}
