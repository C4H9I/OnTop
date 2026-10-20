package com.ontop.app.sharing

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.util.UUID

data class IncomingShare(val id: String = UUID.randomUUID().toString(), val title: String,
    val text: String, val imageUri: String? = null) : java.io.Serializable

internal fun readIncomingShare(intent: Intent): IncomingShare? {
    if (intent.action != Intent.ACTION_SEND) return null
    val image = intent.type?.startsWith("image/") == true
    require(image || intent.type == "text/plain") { "Этот тип материала пока не поддерживается" }
    val clip = intent.clipData
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?: (0 until (clip?.itemCount ?: 0)).firstNotNullOfOrNull { index ->
            clip?.getItemAt(index)?.let { item -> item.text?.toString()
                ?: item.uri?.takeIf { it.scheme in listOf("http", "https") }?.toString() }
        }.orEmpty()
    val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim().orEmpty()
    @Suppress("DEPRECATION")
    val stream = if (image) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
    val uris = if (image) (listOfNotNull(stream) + (0 until (clip?.itemCount ?: 0))
        .mapNotNull { clip?.getItemAt(it)?.uri }).distinct() else emptyList()
    require(uris.size <= 1) { "Пока можно получить только одно изображение за раз" }
    require(!image || uris.singleOrNull()?.scheme == "content") { "Не удалось получить доступ к изображению" }
    require(image || text.isNotBlank() || subject.isNotBlank()) { "В полученном материале нет текста" }
    val title = subject.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
        ?: "Изображение" }
    return IncomingShare(title = title, text = text, imageUri = uris.singleOrNull()?.toString())
}

/** Pending deliveries survive rotation/process recreation; active editor owns consumed drafts. */
class ShareInbox(private val saved: SavedStateHandle) : ViewModel() {
    val queue = saved.getStateFlow("incoming_shares", arrayListOf<IncomingShare>())
    fun enqueue(share: IncomingShare) { saved["incoming_shares"] = ArrayList(queue.value + share) }
    fun consumed(id: String) { saved["incoming_shares"] = ArrayList(queue.value.filterNot { it.id == id }) }
}
