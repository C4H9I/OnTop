package com.ontop.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ontop.app.text.MediaKind
import com.ontop.app.text.NoteMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Metadata belongs to the containing Room card document; bytes never enter SQLite. */
internal class AttachmentStore(private val context: Context) {
    companion object {
        const val MAX_BYTES = 256L * 1024 * 1024
        fun validPath(path: String) = Regex("attachments/[a-f0-9-]{1,64}\\.bin").matches(path)
    }
    suspend fun import(uri: Uri): NoteMedia = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(1024)?.takeIf { it.isNotBlank() } ?: "Файл"
        val suppliedMime = resolver.getType(uri)?.substringBefore(';')?.lowercase()?.take(255)
        val extensionMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        val mime = suppliedMime?.takeUnless { it in setOf("application/octet-stream", "application/ogg") }
            ?: extensionMime ?: suppliedMime ?: "application/octet-stream"
        val path = "attachments/${UUID.randomUUID()}.bin"
        val file = File(context.filesDir, path)
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val size = resolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                com.ontop.app.backup.BackupFormat.copy(input, output, MAX_BYTES)
            } } ?: error("Не удалось прочитать файл")
            val kind = when {
                mime.startsWith("audio/") -> MediaKind.AUDIO
                mime.startsWith("video/") -> MediaKind.LOCAL_VIDEO
                else -> MediaKind.FILE
            }
            val duration = if (kind == MediaKind.FILE) null else runCatching {
                val reader = android.media.MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.absolutePath)
                    reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0)
                } finally { reader.release() }
            }.getOrNull()
            NoteMedia(offset = 0, kind = kind, path = path, name = name, mime = mime, bytes = size, durationMillis = duration)
        } catch (error: Throwable) { file.delete(); throw error }
    }
}
