package com.ontop.app.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardImageStore
import com.ontop.app.data.Checklist
import com.ontop.app.data.checklistItems
import com.ontop.app.text.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Separate component name keeps the production provider distinct from debug-only fixtures. */
class NoteShareProvider : FileProvider()

internal fun sharedNoteText(card: CardEntity): String {
    val document = RichTextCodec.decode(card.description, card.formatting)
    val body = buildString {
        var start = 0
        document.media.filter { it.kind == MediaKind.YOUTUBE }.forEach { media ->
            append(document.text.substring(start, media.offset))
            if (isNotEmpty() && last() != '\n') append('\n')
            append(media.video!!.source).append('\n')
            start = media.offset
        }
        append(document.text.substring(start))
    }.trim()
    return listOf(card.title.trim(), body, Checklist.text(card.checklistItems())).filter { it.isNotBlank() }.joinToString("\n\n")
}

internal data class PreparedShare(val intent: Intent, val imageUnavailable: Boolean = false)

internal object OutgoingShare {
    suspend fun prepare(context: Context, card: CardEntity): PreparedShare = withContext(Dispatchers.IO) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sharedNoteText(card))
        val image = card.imagePath ?: RichTextCodec.decode(card.description, card.formatting).media
            .firstOrNull { it.kind == MediaKind.IMAGE }?.path
        if (image == null) return@withContext PreparedShare(send)
        var copy: File? = null
        try {
            val directory = File(context.cacheDir, "shared-notes").apply { check(isDirectory || mkdirs()) }
            // Keep copies while recipients may still be reading them; prune only our expired cache files.
            directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9-]+\\.jpg")) &&
                System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
            copy = File(directory, "${UUID.randomUUID()}.jpg")
            CardImageStore(context).resolve(image).inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.shares", copy)
            val attachment = Intent(send).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            attachment.clipData = ClipData.newUri(context.contentResolver, "Изображение заметки", uri)
            attachment.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PreparedShare(attachment)
        } catch (cancelled: CancellationException) { copy?.delete(); throw cancelled
        } catch (_: Exception) { copy?.delete(); PreparedShare(send, imageUnavailable = true) }
    }
}
