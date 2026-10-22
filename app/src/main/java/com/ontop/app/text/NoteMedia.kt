package com.ontop.app.text

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

internal enum class MediaKind { IMAGE, YOUTUBE, FILE, AUDIO, LOCAL_VIDEO }
internal data class NoteMedia(val id: String = UUID.randomUUID().toString(), val offset: Int,
    val kind: MediaKind, val path: String? = null, val video: YouTubeLink? = null,
    val name: String? = null, val mime: String? = null, val bytes: Long = 0,
    val durationMillis: Long? = null) : java.io.Serializable {
    val isAttachment get() = kind in setOf(MediaKind.FILE, MediaKind.AUDIO, MediaKind.LOCAL_VIDEO)
    // Old FILE blocks gain inline playback without rewriting their files or database rows.
    val isAudio get() = kind == MediaKind.AUDIO || kind == MediaKind.FILE && mime.orEmpty().startsWith("audio/")
    val isLocalVideo get() = kind == MediaKind.LOCAL_VIDEO || kind == MediaKind.FILE && mime.orEmpty().startsWith("video/")
}

internal data class YouTubeLink(val id: String, val seconds: Int = 0, val source: String) : java.io.Serializable {
    val watchUrl get() = "https://www.youtube.com/watch?v=$id" + if (seconds > 0) "&t=${seconds}s" else ""
    companion object {
        fun parse(input: String): YouTubeLink? = runCatching {
            val original = input.trim()
            val uri = URI(if (original.startsWith("www.") || original.startsWith("youtube.com/") || original.startsWith("youtu.be/")) "https://$original" else original)
            require(uri.scheme in listOf("https", "http") && uri.userInfo == null && uri.port in listOf(-1, 443, 80))
            val host = uri.host?.lowercase()
            require(host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be"))
            fun params(raw: String?) = raw.orEmpty().split('&').filter { it.contains('=') }.associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            val query = params(uri.rawQuery)
            val parts = uri.path.trim('/').split('/')
            val id = if (host!!.endsWith("youtu.be")) { require(parts.size == 1); parts[0] }
                else if (parts == listOf("watch")) query["v"]
                else { require(parts.size == 2 && parts[0] in listOf("shorts", "embed", "live")); parts[1] }
            require(id != null && Regex("[A-Za-z0-9_-]{11}").matches(id))
            val rawTime = query["t"] ?: query["start"] ?: params(uri.rawFragment)["t"]
            val seconds = if (rawTime == null) 0 else {
                val digits = rawTime.toLongOrNull()
                val time = digits ?: run {
                    require(Regex("(?i)(\\d+h)?(\\d+m)?(\\d+s)?").matches(rawTime) && rawTime.isNotEmpty())
                    Regex("(?i)(\\d+)([hms])").findAll(rawTime).sumOf { match ->
                        Math.multiplyExact(match.groupValues[1].toLong(), when (match.groupValues[2].lowercase()) { "h" -> 3600L; "m" -> 60L; else -> 1L })
                    }
                }
                require(time in 0..Int.MAX_VALUE.toLong()); time.toInt()
            }
            YouTubeLink(id, seconds, original)
        }.getOrNull()
    }
}

internal data class NoteTextPiece(val key: String, val index: Int, val start: Int, val end: Int)
internal fun RichDocument.pieces(): List<NoteTextPiece> = (0..media.size).map { index ->
    NoteTextPiece(if (index == 0) "body" else media[index - 1].id, index,
        if (index == 0) 0 else media[index - 1].offset, if (index == media.size) text.length else media[index].offset)
}
internal fun RichDocument.fragment(piece: NoteTextPiece): RichDocument = RichDocument(text.substring(piece.start, piece.end),
    ranges.mapNotNull { r ->
        val start = maxOf(r.start, piece.start); val end = minOf(r.end, piece.end)
        if (start < end || (r.kind.block && r.start == r.end && r.start in piece.start..piece.end))
            r.copy(start = start - piece.start, end = end - piece.start) else null
    }).normalized()

/** Fragment edits cannot consume adjacent media. Equal-position blocks retain their list order. */
internal fun RichDocument.replacePiece(key: String, fragment: RichDocument): RichDocument {
    val piece = pieces().firstOrNull { it.key == key } ?: return this
    val delta = fragment.text.length - (piece.end - piece.start)
    val outer = ranges.flatMap { r -> buildList {
        if (r.start < piece.start) add(r.copy(end = minOf(r.end, piece.start)))
        if (r.end > piece.end) add(r.copy(start = maxOf(r.start, piece.end) + delta, end = r.end + delta))
    } }
    return copy(text = text.replaceRange(piece.start, piece.end, fragment.text),
        ranges = outer + fragment.ranges.map { it.copy(start = it.start + piece.start, end = it.end + piece.start) },
        media = media.mapIndexed { index, block -> if (index >= piece.index) block.copy(offset = block.offset + delta) else block }).normalized()
}

internal fun RichDocument.insertMedia(key: String, cursor: Int, block: NoteMedia): RichDocument {
    val piece = pieces().firstOrNull { it.key == key } ?: return this
    val fragment = fragment(piece)
    val position = cursor.coerceIn(0, fragment.text.length)
    val needsBreak = position > 0 && fragment.text[position - 1] != '\n'
    val updated = if (needsBreak) replacePiece(key, fragment.replace(position, position, "\n")) else this
    val offset = piece.start + position + if (needsBreak) 1 else 0
    return updated.copy(media = updated.media.toMutableList().apply { add(piece.index, block.copy(offset = offset)) }).normalized()
}
internal fun RichDocument.imagePaths(): Set<String> = media.filter { it.kind == MediaKind.IMAGE }.mapNotNull { it.path }.toSet()

/** Preserve the paragraph boundary when its last media separator is removed. */
internal fun RichDocument.removeMedia(id: String): RichDocument {
    val index = media.indexOfFirst { it.id == id }
    if (index < 0) return this
    val at = media[index].offset
    val needsBreak = at > 0 && at < text.length && text[at - 1] != '\n' && text[at] != '\n' &&
        media.none { it.id != id && it.offset == at }
    val separated = if (needsBreak) {
        val piece = pieces()[index]
        val before = fragment(piece)
        replacePiece(piece.key, before.replace(before.text.length, before.text.length, "\n"))
    } else this
    return separated.copy(media = separated.media.filterNot { it.id == id }).normalized()
}

internal fun RichDocument.resourcePaths(): Set<String> = media.mapNotNull { it.path }.toSet()
