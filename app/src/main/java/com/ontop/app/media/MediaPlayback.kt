package com.ontop.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer

/** A single lazy engine for note audio, recording previews and local video. Main thread only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MediaPlayback private constructor(private val context: Context) {
    companion object {
        private var instance: MediaPlayback? = null
        fun get(context: Context): MediaPlayback = instance ?: MediaPlayback(context.applicationContext).also { instance = it }
    }
    var key by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var duration by mutableLongStateOf(0); private set
    var position by mutableLongStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var engine by mutableStateOf<ExoPlayer?>(null); private set
    private var references = 0
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            update()
            if (engine?.playWhenReady == true) handler.postDelayed(this, 250)
        }
    }
    private fun update() {
        val player = engine
        playing = player?.isPlaying == true
        position = player?.currentPosition?.coerceAtLeast(0) ?: 0
        duration = player?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0
    }
    fun attach() { references++ }
    fun detach() {
        references--
        if (references <= 0) {
            references = 0; stop(); engine?.release(); engine = null
        }
    }
    fun stopIf(owner: String) { if (key == owner) stop() }
    fun pauseIf(owner: String) { if (key == owner) { engine?.pause(); update() } }
    fun stop() {
        handler.removeCallbacks(ticker)
        engine?.pause(); engine?.stop(); engine?.clearMediaItems()
        key = null; error = null; update()
    }
    fun toggle(owner: String, uri: Uri) {
        val player = engine ?: ExoPlayer.Builder(context).build().also { created ->
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            created.setHandleAudioBecomingNoisy(true)
            created.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    update(); handler.removeCallbacks(ticker)
                    if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) handler.post(ticker)
                }
                override fun onPlayerError(failure: PlaybackException) {
                    error = "Не удалось воспроизвести файл. Формат может не поддерживаться или файл повреждён."
                    created.pause(); update()
                }
            })
            engine = created
        }
        if (key != owner || error != null) {
            stop(); key = owner; error = null
            player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.play()
        } else if (player.isPlaying || player.playWhenReady) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        update()
    }
    fun seek(owner: String, at: Long) {
        if (key == owner) { engine?.seekTo(at.coerceIn(0, duration.coerceAtLeast(0))); update() }
    }
}

internal fun mediaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
