package com.ontop.app.text

import org.json.JSONArray
import org.json.JSONObject

/** Styles and media references are JSON; the authoritative text remains the plain description. */
internal object RichTextCodec {
    fun decode(text: String, formatting: String?): RichDocument {
        if (formatting.isNullOrBlank()) return RichDocument(text)
        var media = emptyList<NoteMedia>()
        val ranges = runCatching {
            val root = JSONObject(formatting)
            if (root.getInt("v") !in 1..2) return RichDocument(text)
            val blocks = root.optJSONArray("media")
            if (blocks != null) media = (0 until blocks.length()).mapNotNull { index -> runCatching {
                val block = blocks.getJSONObject(index)
                val kind = MediaKind.valueOf(block.getString("kind"))
                val offset = block.getInt("at")
                require(offset in 0..text.length)
                val id = block.getString("id"); require(id.isNotBlank())
                if (kind == MediaKind.IMAGE) {
                    val path = block.getString("path")
                    require(Regex("images/[a-f0-9-]+\\.jpg").matches(path))
                    NoteMedia(id, offset, kind, path = path)
                } else {
                    val video = YouTubeLink.parse(block.getString("url")) ?: error("Invalid video")
                    NoteMedia(id, offset, kind, video = video)
                }
            }.getOrNull() }
            val entries = root.getJSONArray("ranges")
            (0 until entries.length()).mapNotNull { index -> runCatching {
                val entry = entries.getJSONObject(index)
                RichRange(entry.getInt("s"), entry.getInt("e"), RichKind.valueOf(entry.getString("k")),
                    if (entry.has("c")) entry.getString("c") else null)
            }.getOrNull() }
        }.getOrDefault(emptyList())
        return RichDocument(text, ranges, media).normalized()
    }

    fun encode(document: RichDocument): String? {
        val ranges = document.normalized().ranges
        if (ranges.isEmpty() && document.media.isEmpty()) return null
        return JSONObject().put("v", if (document.media.isEmpty()) 1 else 2).put("ranges", JSONArray().apply {
            ranges.forEach { range -> put(JSONObject().put("s", range.start).put("e", range.end).put("k", range.kind.name).apply {
                range.color?.let { put("c", it) }
            }) }
        }).apply {
            if (document.media.isNotEmpty()) put("media", JSONArray().apply {
                document.media.forEach { block -> put(JSONObject().put("id", block.id).put("at", block.offset).put("kind", block.kind.name).apply {
                    if (block.kind == MediaKind.IMAGE) put("path", block.path) else put("url", block.video!!.source)
                }) }
            })
        }.toString()
    }
}
