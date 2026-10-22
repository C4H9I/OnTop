package com.ontop.app.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ontop.app.gallery.CameraCaptureStore
import kotlinx.coroutines.delay

@Composable
internal fun AudioRecordingDialog(onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val captures = remember { CameraCaptureStore(context.applicationContext) }
    var source by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<VoiceRecorder?>(null) }
    var retained by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        try {
            val name = captures.prepare("m4a")
            source = name
            session = VoiceRecorder(context.applicationContext, captures.file(name))
        } catch (_: Exception) { error = "Не удалось создать временную запись. Проверьте свободное место." }
    }
    DisposableEffect(lifecycle, session) {
        val engine = session
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) engine?.interrupt("Запись остановлена при уходе приложения в фон. Её можно прослушать и сохранить.")
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); engine?.close(keep = retained) }
    }
    LaunchedEffect(session, session?.state) {
        do { elapsed = session?.elapsed ?: 0; delay(200) } while (session?.recording == true)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) session?.start()
        else if (!granted) error = "Доступ к микрофону не предоставлен. Можно выбрать готовый аудиофайл."
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Записать аудио") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("audio-recorder")) {
            Text(mediaTime(elapsed), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("recording-time"))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            session?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val engine = session
            if (engine?.state == RecordingState.PREVIEW) {
                source?.let { name -> LocalMediaPlayer("preview-$name", Uri.fromFile(captures.file(name)), "Голосовая заметка",
                    knownDuration = engine.elapsed) }
            } else if (engine?.recording == true) {
                Text(if (engine.state == RecordingState.PAUSED) "Запись на паузе" else "Идёт запись")
                TextButton(onClick = engine::pauseOrResume, modifier = Modifier.testTag("recording-pause")) {
                    Text(if (engine.state == RecordingState.PAUSED) "Продолжить" else "Пауза")
                }
                TextButton(onClick = engine::finish, modifier = Modifier.testTag("recording-finish")) { Text("Завершить запись") }
            } else TextButton(enabled = engine != null, modifier = Modifier.testTag("recording-start"), onClick = {
                error = null
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    engine?.start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text("Начать запись") }
        }
    }, confirmButton = {
        TextButton(enabled = session?.state == RecordingState.PREVIEW, modifier = Modifier.testTag("recording-insert"), onClick = {
            source?.let { name ->
                MediaPlayback.get(context).stopIf("preview-$name")
                retained = true; onInsert(name)
            }
        }) { Text("Вставить в заметку") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
