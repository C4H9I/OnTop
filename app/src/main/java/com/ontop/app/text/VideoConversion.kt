package com.ontop.app.text

internal data class VideoLinkOccurrence(val start: Int, val end: Int, val video: YouTubeLink)

/** Match individual URLs, never consume the surrounding paragraph or another media block. */
internal fun youtubeLinks(text: String): List<VideoLinkOccurrence> =
    Regex("(?i)(?<![\\p{L}\\p{N}_/@.-])(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/[^\\s<>\"«»]+")
        .findAll(text).mapNotNull { match ->
            val url = match.value.trimEnd('.', ',', ';', '!', ')', ']', '}', '…')
            YouTubeLink.parse(url)?.let { VideoLinkOccurrence(match.range.first, match.range.first + url.length, it) }
        }.toList()

internal fun RichDocument.linkToVideo(key: String, link: VideoLinkOccurrence): RichDocument {
    val piece = pieces().firstOrNull { it.key == key } ?: return this
    val fragment = fragment(piece)
    if (link.start !in 0..fragment.text.length || link.end !in link.start..fragment.text.length ||
        fragment.text.substring(link.start, link.end) != link.video.source) return this
    val updated = replacePiece(key, fragment.replace(link.start, link.end, ""))
    val block = NoteMedia(offset = piece.start + link.start, kind = MediaKind.YOUTUBE, video = link.video)
    return updated.copy(media = updated.media.toMutableList().apply { add(piece.index, block) }).normalized()
}

internal fun RichDocument.videoToLink(id: String): RichDocument {
    val index = media.indexOfFirst { it.id == id && it.kind == MediaKind.YOUTUBE }
    if (index < 0) return this
    val before = pieces()[index]
    val fragment = fragment(before)
    val source = media[index].video!!.source
    fun insert(value: String): RichDocument {
        val updated = replacePiece(before.key, fragment.replace(fragment.text.length, fragment.text.length, value))
        return updated.copy(media = updated.media.filterNot { it.id == id }).normalized()
    }
    // Edit the fragment before this block so consecutive equal-offset media retain their order.
    val exact = insert(source)
    val joined = exact.fragment(exact.pieces().first { it.key == before.key }).text
    if (youtubeLinks(joined).any { it.start == fragment.text.length && it.end == fragment.text.length + source.length }) return exact
    // A manually inserted block may separate words without any newline in the plain text.
    // Retain those words, but keep the restored URL independently recognisable.
    val after = fragment(pieces()[index + 1]).text
    val prefix = if (fragment.text.isNotEmpty() && !fragment.text.last().isWhitespace()) "\n" else ""
    val suffix = if (after.isNotEmpty() && !after.first().isWhitespace()) "\n" else ""
    return insert(prefix + source + suffix)
}

internal fun RichDocument.convertYouTubeLinks(): RichDocument {
    var result = this
    // Right to left: earlier offsets remain valid, including multiple links in one paragraph.
    pieces().asReversed().forEach { piece ->
        youtubeLinks(fragment(piece).text).asReversed().forEach { link -> result = result.linkToVideo(piece.key, link) }
    }
    return result
}
