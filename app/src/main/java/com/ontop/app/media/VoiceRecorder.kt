package com.ontop.app.media

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import com.ontop.app.data.AttachmentStore
import java.io.File

internal enum class RecordingState { IDLE, RECORDING, PAUSED, PREVIEW, ERROR }

/** A foreground-only session. Interruptions finalize a usable recording or discard an invalid one. */
internal class VoiceRecorder(private val context: Context, val file: File) {
    var state by mutableStateOf(RecordingState.IDLE); private set
    var message by mutableStateOf<String?>(null); private set
    private var recorder: MediaRecorder? = null
    private var recorded = 0L
    private var started = 0L
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    val elapsed get() = recorded + if (state == RecordingState.RECORDING) SystemClock.elapsedRealtime() - started else 0L
    val recording get() = state == RecordingState.RECORDING || state == RecordingState.PAUSED

    @Suppress("DEPRECATION")
    fun start() {
        check(!recording)
        MediaPlayback.get(context).stop()
        message = null; recorded = 0
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                        interrupt("Запись остановлена: микрофон нужен другому приложению или звонку.")
                }, Handler(Looper.getMainLooper())).build()
            focus = request
            check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val source = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = source
            source.setAudioSource(MediaRecorder.AudioSource.MIC)
            source.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            source.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            source.setAudioChannels(1); source.setAudioSamplingRate(44100); source.setAudioEncodingBitRate(128000)
            source.setOutputFile(file.absolutePath)
            source.setMaxFileSize(AttachmentStore.MAX_BYTES)
            source.setOnErrorListener { _, _, _ -> fail("Запись прервана. Проверьте доступ к микрофону и свободное место.") }
            source.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) interrupt("Достигнут максимальный размер записи.")
            }
            source.prepare(); source.start()
            started = SystemClock.elapsedRealtime(); state = RecordingState.RECORDING
            if (Build.VERSION.SDK_INT >= 29) {
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (recorder !== source || !recording) return
                        if (runCatching { source.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false))
                            interrupt("Запись остановлена: микрофон временно недоступен.")
                    }
                }
                recordingCallback = callback
                audio.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            }
        } catch (_: Exception) { fail("Не удалось начать запись. Разрешите доступ к микрофону и проверьте свободное место.") }
    }
    fun pauseOrResume() {
        try {
            if (state == RecordingState.RECORDING) {
                val time = elapsed
                recorder!!.pause(); recorded = time; state = RecordingState.PAUSED
            } else if (state == RecordingState.PAUSED) {
                recorder!!.resume(); started = SystemClock.elapsedRealtime(); state = RecordingState.RECORDING
            }
        } catch (_: Exception) { interrupt("Запись остановлена: пауза не поддерживается устройством.") }
    }
    fun finish() {
        if (!recording) return
        val time = elapsed
        try {
            recorder!!.stop(); recorded = time; state = RecordingState.PREVIEW
            release()
            check(file.length() > 0)
        } catch (_: Exception) { fail("Запись слишком короткая или не завершена. Попробуйте ещё раз.") }
    }
    fun interrupt(reason: String) {
        if (recording) { finish(); if (state == RecordingState.PREVIEW) message = reason }
    }
    private fun fail(reason: String) {
        state = RecordingState.ERROR; message = reason
        release(); file.delete()
    }
    private fun release() {
        val previous = recorder; recorder = null
        runCatching { previous?.release() }
        recordingCallback?.let { audio.unregisterAudioRecordingCallback(it) }; recordingCallback = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
    fun close(keep: Boolean = false) {
        state = RecordingState.IDLE
        release()
        if (!keep) file.delete()
    }
}
