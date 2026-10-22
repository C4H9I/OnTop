package com.ontop.app.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Only temporary, app-created captures are exposed to the external camera. */
internal class CameraCaptureStore(private val context: Context) {
    fun file(name: String): File {
        require(Regex("[a-f0-9-]{36}\\.(jpg|mp4|m4a)").matches(name))
        return File(File(context.cacheDir, "camera"), name)
    }
    fun uri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.shares", file(name))
    fun delete(name: String) { file(name).delete() }
    suspend fun prepare(extension: String = "jpg"): String = withContext(Dispatchers.IO) {
        require(extension in setOf("jpg", "mp4", "m4a"))
        val name = "${UUID.randomUUID()}.$extension"
        val target = file(name)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        target.parentFile!!.listFiles()?.filter {
            it.isFile && Regex("[a-f0-9-]{36}\\.(jpg|mp4|m4a)").matches(it.name) &&
                System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L
        }?.forEach { it.delete() }
        check(target.createNewFile())
        name
    }
}

internal class CaptureNoteVideo : ActivityResultContracts.CaptureVideo() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("Видео заметки", input)
        putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, com.ontop.app.data.AttachmentStore.MAX_BYTES)
    }
}

internal class TakeNotePhoto : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("Фото заметки", input)
    }
}

@Composable
internal fun rememberCameraCapture(owner: Long?, onCaptured: (String) -> Unit, onFailure: () -> Unit,
    video: Boolean = false): () -> Unit {
    val context = LocalContext.current
    val store = remember(context) { CameraCaptureStore(context.applicationContext) }
    var name by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onFailure)
    val contract: androidx.activity.result.contract.ActivityResultContract<Uri, Boolean> = remember(video) {
        if (video) CaptureNoteVideo() else TakeNotePhoto()
    }
    val launcher = rememberLauncherForActivityResult(contract) { success ->
        val source = name
        name = null
        if (source != null) {
            if (success) captured(source)
            else scope.launch(Dispatchers.IO) { store.delete(source) }
        }
    }
    return {
        if (name == null && !preparing) {
            preparing = true
            scope.launch {
                try {
                    name = store.prepare(if (video) "mp4" else "jpg")
                    launcher.launch(store.uri(name!!))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) {
                    name?.let { source -> withContext(Dispatchers.IO) { store.delete(source) } }
                    name = null
                    failed()
                } finally { preparing = false }
            }
        }
    }
}
