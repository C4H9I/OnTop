package com.ontop.app.backup

import android.graphics.BitmapFactory
import com.ontop.app.data.*
import com.ontop.app.reminders.schedule
import com.ontop.app.text.*
import com.ontop.app.drawing.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.*
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class BackupData(val cards: List<CardEntity>, val comments: List<CardCommentEntity>,
    val reminders: List<ReminderEntity>, val tags: List<TagEntity> = emptyList(), val cardTags: List<CardTagCrossRef> = emptyList(),
    val interactions: List<NoteInteractionEntity> = emptyList()) {
    fun withoutTrash(): BackupData {
        val retained = cards.filter { it.status != CardStatus.TRASHED }
        val ids = retained.map { it.id }.toSet()
        return copy(cards = retained, comments = comments.filter { it.cardId in ids },
            reminders = reminders.filter { it.cardId in ids }, cardTags = cardTags.filter { it.cardId in ids },
            interactions = interactions.filter { it.noteId in ids })
    }
    fun paths(): Set<String> = cards.flatMap { card ->
        listOfNotNull(card.imagePath) + RichTextCodec.decode(card.description, card.formatting).resourcePaths()
    }.toSet()
}

internal data class BackupLimits(val archive: Long = 512L * 1024 * 1024,
    val unpacked: Long = 512L * 1024 * 1024, val image: Long = 16L * 1024 * 1024,
    val json: Long = 32L * 1024 * 1024, val entries: Int = 10002,
    val attachment: Long = AttachmentStore.MAX_BYTES) {
    fun resource(path: String) = if (AttachmentStore.validPath(path)) attachment else image
}

/** A logical export, independent of SQLite schema/version and Android filesystem locations. */
internal object BackupFormat {
    const val VERSION = 11
    private val imageName = Regex("images/[a-f0-9-]{1,64}\\.(jpg|png)")
    fun validPath(path: String): Boolean = imageName.matches(path)
    fun validResource(path: String): Boolean = AttachmentStore.validPath(path) || validPath(path) || Regex("images/[a-f0-9-]{1,64}\\.(drawing\\.json|source)").matches(path)
    private fun JSONObject.required(name: String): Any { require(has(name)); return get(name) }
    private fun JSONObject.string(name: String, maximum: Int = 1_048_576): String =
        (required(name) as? String)?.also { require(it.length <= maximum) } ?: error("Invalid $name")
    private fun JSONObject.number(name: String): Long {
        val value = required(name)
        require(value is Int || value is Long) { "Invalid integer $name" }
        return (value as Number).toLong()
    }
    private fun JSONObject.int(name: String): Int = number(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JSONObject.id(name: String) = number(name).also { require(it in 1 until (1L shl 53)) }
    private fun JSONObject.time(name: String) = number(name).also { require(it in 0..253402300799999L) }
    private fun JSONObject.nullTime(name: String): Long? = if (required(name) == JSONObject.NULL) null else time(name)
    private fun JSONObject.nullString(name: String, maximum: Int = 8_388_608): String? =
        if (required(name) == JSONObject.NULL) null else string(name, maximum)
    private fun JSONObject.bool(name: String) = required(name) as? Boolean ?: error("Invalid $name")
    private fun JSONObject.list(name: String, maximum: Int): List<JSONObject> {
        val array = required(name) as? JSONArray ?: error("Invalid $name")
        require(array.length() <= maximum)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun JSONObject.nullable(name: String, value: Any?) = put(name, value ?: JSONObject.NULL)

    private fun objectFrom(text: String): JSONObject {
        // Reject pathological nesting before the recursive platform JSON parser sees it.
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false else when (character) { '\\' -> escaped = true; '"' -> quoted = false }
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        val parser = JSONTokener(text)
        val value = parser.nextValue() as? JSONObject ?: error("Expected JSON object")
        require(parser.nextClean() == '\u0000')
        return value
    }

    fun document(text: String, formatting: String?): RichDocument {
        if (formatting == null) return RichDocument(text)
        val json = objectFrom(formatting)
        val version = json.int("v"); require(version in 1..4)
        val ranges = json.list("ranges", 100_000).map { range ->
            val kind = RichKind.valueOf(range.string("k", 30))
            val start = range.int("s"); val end = range.int("e")
            require(start in 0..text.length && end in start..text.length)
            val color = if (range.has("c")) range.string("c", 40) else null
            if (kind == RichKind.COLOR) require(validTextColor(color))
            RichRange(start, end, kind, color)
        }
        val media = if (version == 1) {
            require(!json.has("media")); emptyList()
        } else json.list("media", 100_000).map { block ->
            val id = block.string("id", 100); require(id != "body" && Regex("[A-Za-z0-9_-]{1,100}").matches(id))
            val at = block.int("at"); require(at in 0..text.length)
            val kind = MediaKind.valueOf(block.string("kind", 20))
            if (kind == MediaKind.IMAGE) {
                val path = block.string("path", 100); require(validPath(path))
                NoteMedia(id, at, kind, path = path)
            } else if (kind in setOf(MediaKind.FILE, MediaKind.AUDIO, MediaKind.LOCAL_VIDEO)) {
                val path = block.string("path", 100); require(AttachmentStore.validPath(path))
                val name = block.string("name", 1024); require(name.isNotBlank())
                val mime = block.string("mime", 255)
                val bytes = block.number("bytes"); require(bytes in 0..BackupLimits().attachment)
                if (kind == MediaKind.AUDIO) require(mime.startsWith("audio/"))
                if (kind == MediaKind.LOCAL_VIDEO) require(mime.startsWith("video/"))
                val duration = if (block.has("duration")) block.number("duration").also { require(it >= 0) } else null
                NoteMedia(id, at, kind, path = path, name = name, mime = mime, bytes = bytes, durationMillis = duration)
            } else NoteMedia(id, at, kind, video = YouTubeLink.parse(block.string("url", 8192)) ?: error("Invalid YouTube URL"))
        }
        require(media.map { it.id }.distinct().size == media.size)
        require(media.zipWithNext().all { (a, b) -> a.offset <= b.offset })
        return RichDocument(text, ranges, media).normalized()
    }

    fun encode(data: BackupData, includeTags: Boolean = true, includeStatus: Boolean = true,
        includeProgress: Boolean = true, includeInteractions: Boolean = true, includeChecklist: Boolean = true): ByteArray = JSONObject().put("cards", JSONArray().apply {
        data.cards.sortedBy { it.id }.forEach { c -> put(JSONObject().put("id", c.id).put("title", c.title)
            .put("description", c.description).put("size", c.size.name).put("createdAt", c.createdAt)
            .put("updatedAt", c.updatedAt).put("sortOrder", c.sortOrder).put("importance", c.importance)
            .nullable("imagePath", c.imagePath).nullable("formatting", c.formatting)
            .apply {
                if (includeStatus) put("status", c.status.name)
                if (includeProgress) nullable("progress", c.progress)
                if (includeChecklist) {
                    put("noteType", c.noteType.name)
                    put("checklist", JSONArray(Checklist.encode(c.checklistItems())))
                }
            }) }
    }).put("comments", JSONArray().apply {
        data.comments.sortedBy { it.id }.forEach { c -> put(JSONObject().put("id", c.id).put("cardId", c.cardId)
            .put("text", c.text).put("createdAt", c.createdAt)) }
    }).put("reminders", JSONArray().apply {
        data.reminders.sortedBy { it.id }.forEach { r -> put(JSONObject().put("id", r.id).put("cardId", r.cardId)
            .put("hour", r.hour).put("minute", r.minute).put("enabled", r.enabled)
            .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt).nullable("nextAt", r.nextAt)
            .nullable("snoozeAt", r.snoozeAt).put("mode", r.mode).put("daysMask", r.daysMask).put("times", r.times)
            .put("intervalHours", r.intervalHours).put("windowStart", r.windowStart).put("windowEnd", r.windowEnd)
            .nullable("quietStart", r.quietStart).nullable("quietEnd", r.quietEnd).nullable("pausedUntil", r.pausedUntil)) }
    }).apply {
        if (includeInteractions) put("interactions", JSONArray().apply {
            data.interactions.sortedBy { it.id }.forEach { event -> put(JSONObject().put("id", event.id)
                .put("noteId", event.noteId).put("eventType", event.eventType).put("timestamp", event.timestamp)) }
        })
        if (includeTags) {
            put("tags", JSONArray().apply { data.tags.sortedBy { it.id }.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt))
            } })
            put("cardTags", JSONArray().apply { data.cardTags.sortedWith(compareBy<CardTagCrossRef> { it.cardId }.thenBy { it.tagId }).forEach {
                put(JSONObject().put("cardId", it.cardId).put("tagId", it.tagId))
            } })
        }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray, requireTags: Boolean = false, requireStatus: Boolean = false,
        requireProgress: Boolean = false, requireInteractions: Boolean = false, requireChecklist: Boolean = false): BackupData {
        val root = objectFrom(bytes.toString(Charsets.UTF_8))
        require(root.has("tags") == root.has("cardTags"))
        if (requireTags) require(root.has("tags"))
        val cards = root.list("cards", 10_000).map { c ->
            if (requireChecklist) require(c.has("noteType") && c.has("checklist"))
            val type = if (c.has("noteType")) NoteType.valueOf(c.string("noteType", 20)) else NoteType.NOTE
            val checklist = if (c.has("checklist")) Checklist.decode(c.getJSONArray("checklist").toString()) else emptyList()
            require(type == NoteType.CHECKLIST || checklist.isEmpty())
            CardEntity(c.id("id"), c.string("title", 10_000).also { require(it.isNotBlank()) }, c.string("description"),
                CardSize.valueOf(c.string("size", 20)), c.time("createdAt"), c.time("updatedAt"), c.number("sortOrder"),
                c.nullString("imagePath", 100)?.also { require(validPath(it)) }, c.nullString("formatting"),
                if (c.has("importance")) c.int("importance").also { require(it in 0..100) } else 50,
                if (c.has("status")) CardStatus.valueOf(c.string("status", 20)) else {
                    require(!requireStatus); CardStatus.ACTIVE
                }, if (c.has("progress") && !c.isNull("progress")) c.int("progress").also { require(it in 0..100) }
                    else { require(!requireProgress || c.has("progress")); null }, type,
                if (type == NoteType.CHECKLIST) Checklist.encode(checklist) else null).normalizedChecklist()
                .also { document(it.description, it.formatting) }
        }
        val comments = root.list("comments", 100_000).map { c ->
            CardCommentEntity(c.id("id"), c.id("cardId"), c.string("text"), c.time("createdAt"))
        }
        val reminders = root.list("reminders", 10_000).map { r ->
            fun nullableInt(name: String): Int? = if (r.required(name) == JSONObject.NULL) null else r.int(name)
            ReminderEntity(r.id("id"), r.id("cardId"), r.int("hour"), r.int("minute"), r.bool("enabled"),
                r.time("createdAt"), r.time("updatedAt"), r.nullTime("nextAt"), r.nullTime("snoozeAt"),
                r.string("mode", 20), r.int("daysMask"), r.string("times", 8192), r.int("intervalHours"),
                r.int("windowStart"), r.int("windowEnd"), nullableInt("quietStart"), nullableInt("quietEnd"), r.nullTime("pausedUntil"))
                .also { require(it.hour in 0..23 && it.minute in 0..59); it.schedule().validate() }
        }
        val ids = cards.map { it.id }.toSet()
        require(ids.size == cards.size && comments.map { it.id }.distinct().size == comments.size)
        require(reminders.map { it.id }.distinct().size == reminders.size && reminders.map { it.cardId }.distinct().size == reminders.size)
        require(comments.all { it.cardId in ids } && reminders.all { it.cardId in ids })
        val tags = if (root.has("tags")) root.list("tags", 10_000).map {
            val name = cleanTagName(it.string("name", 1000))
            TagEntity(it.id("id"), name, tagNameKey(name), it.time("createdAt"))
        } else emptyList()
        val links = if (root.has("cardTags")) root.list("cardTags", 100_000).map {
            CardTagCrossRef(it.id("cardId"), it.id("tagId"))
        } else emptyList()
        val tagIds = tags.map { it.id }.toSet()
        require(tagIds.size == tags.size && tags.map { it.normalizedName }.toSet().size == tags.size)
        require(links.toSet().size == links.size && links.all { it.cardId in ids && it.tagId in tagIds })
        if (requireInteractions) require(root.has("interactions"))
        val interactions = if (root.has("interactions")) root.list("interactions", 1_000_000).map {
            NoteInteractionEntity(it.id("id"), it.id("noteId"),
                NoteInteractionType.valueOf(it.string("eventType", 40)).name, it.time("timestamp"))
        } else emptyList()
        require(interactions.map { it.id }.toSet().size == interactions.size)
        require(interactions.all { it.noteId in ids })
        return BackupData(cards, comments, reminders, tags, links, interactions)
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun fingerprint(data: BackupData, includeTags: Boolean = true, includeStatus: Boolean = true,
        includeProgress: Boolean = true, includeInteractions: Boolean = true, includeChecklist: Boolean = true): String = digest(encode(data.copy(
        reminders = data.reminders.map { it.copy(nextAt = null, snoozeAt = null) }), includeTags, includeStatus, includeProgress, includeInteractions, includeChecklist))

    /** Enforce limits against actual bytes read; ZIP metadata is untrusted. */
    fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            total += count; require(total <= limit) { "Превышен допустимый размер резервной копии." }
            output.write(buffer, 0, count)
        }
        return total
    }

    fun write(source: BackupData, image: (String) -> File, output: OutputStream, limits: BackupLimits = BackupLimits()) {
        val data = source.withoutTrash()
        val bytes = encode(data); require(bytes.size <= limits.json)
        decode(bytes) // Never produce a backup that this version cannot read.
        data.cards.flatMap { document(it.description, it.formatting).media }.filter { it.isAttachment }.forEach {
            require(image(it.path!!).length() == it.bytes)
        }
        data.paths().filter(DrawingStore::isDrawing).forEach { DrawingStore.validateResources(it, image) }
        val paths = data.paths().flatMap(DrawingStore::resources)
            .filter { !it.endsWith(".source") || image(it).isFile }.sorted(); require(paths.size + 2 <= limits.entries)
        val files = paths.associateWith { path -> image(path).also {
            require(it.isFile && it.length() <= limits.resource(path))
            if (path.endsWith(".json")) { require(it.length() <= 8_000_000); DrawingCodec.decode(it.readText(Charsets.UTF_8)) }
            else if (!AttachmentStore.validPath(path)) checkImage(it)
        } }
        val inventory = JSONArray().put(JSONObject().put("path", "data.json").put("bytes", bytes.size).put("sha256", digest(bytes)))
        var total = bytes.size.toLong()
        files.forEach { (path, file) ->
            total += file.length(); require(total <= limits.unpacked)
            inventory.put(JSONObject().put("path", path).put("bytes", file.length()).put("sha256", file.inputStream().use(::sha)))
        }
        val manifest = JSONObject().put("format", "OnTop").put("version", VERSION).put("createdAt", System.currentTimeMillis())
            .put("files", inventory).toString().toByteArray(Charsets.UTF_8)
        require(total + manifest.size <= limits.unpacked)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, input: InputStream, maximum: Long) {
                zip.putNextEntry(ZipEntry(name)); input.use { copy(it, zip, maximum) }; zip.closeEntry()
            }
            entry("manifest.json", manifest.inputStream(), 4L * 1024 * 1024)
            entry("data.json", bytes.inputStream(), limits.json)
            files.forEach { (path, file) -> entry(path, file.inputStream(), limits.resource(path)) }
        }
    }

    private fun sha(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(archive: File, target: File, limits: BackupLimits = BackupLimits()): BackupData {
        require(archive.length() <= limits.archive)
        val extracted = linkedMapOf<String, Pair<Long, String>>()
        var total = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement(); val name = entry.name
                require(!entry.isDirectory && (name in listOf("manifest.json", "data.json") || validResource(name))) { "Недопустимый путь в архиве." }
                require(name !in extracted && extracted.size < limits.entries) { "Повторяющиеся или лишние файлы." }
                val maximum = when (name) { "manifest.json" -> 4L * 1024 * 1024; "data.json" -> limits.json; else -> limits.resource(name) }
                require(entry.size in 0..maximum)
                val file = File(target, name)
                require(file.canonicalPath.startsWith(target.canonicalPath + File.separator))
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val crc = CRC32(); val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n; total += n
                        require(count <= maximum && total <= limits.unpacked) { "Превышен размер распакованных данных." }
                        output.write(buffer, 0, n); crc.update(buffer, 0, n); digest.update(buffer, 0, n)
                    }
                } }
                require(count == entry.size && crc.value == entry.crc) { "Нарушена целостность ZIP." }
                extracted[name] = count to digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        require("manifest.json" in extracted && "data.json" in extracted)
        val manifest = objectFrom(File(target, "manifest.json").readText(Charsets.UTF_8))
        require(manifest.string("format", 20) == "OnTop")
        val archiveVersion = manifest.int("version")
        require(archiveVersion in 1..VERSION) { "Версия резервной копии не поддерживается." }
        manifest.time("createdAt")
        val inventory = manifest.list("files", limits.entries)
        val names = inventory.map { it.string("path", 100) }
        require(names.distinct().size == names.size && names.toSet() == extracted.keys - "manifest.json")
        inventory.forEach { file ->
            val actual = extracted.getValue(file.string("path", 100))
            val hash = file.string("sha256", 64); require(Regex("[a-f0-9]{64}").matches(hash))
            require(actual.first == file.number("bytes") && actual.second == hash) { "Контрольная сумма не совпадает." }
        }
        val data = decode(File(target, "data.json").readBytes(), requireTags = archiveVersion >= 2,
            requireStatus = archiveVersion >= 3, requireProgress = archiveVersion >= 4, requireInteractions = archiveVersion >= 5,
            requireChecklist = archiveVersion >= 6)
        require(data.paths().flatMap(DrawingStore::resources).filter { !it.endsWith(".source") || it in extracted }.toSet() == extracted.keys - setOf("manifest.json", "data.json")) { "В архиве отсутствуют медиафайлы или есть лишние файлы." }
        data.paths().filterNot(AttachmentStore::validPath).forEach { path -> checkImage(File(target, path)) }
        data.cards.flatMap { document(it.description, it.formatting).media }.filter { it.isAttachment }.forEach {
            require(File(target, it.path!!).length() == it.bytes)
        }
        data.paths().filter(DrawingStore::isDrawing).forEach { path ->
            DrawingStore.validateResources(path) { File(target, it) }
        }
        return data
    }

    private fun checkImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (file.extension == "source") {
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp") && bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000)
        } else require(bounds.outMimeType == (if (file.extension == "png") "image/png" else "image/jpeg") && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 16 })
            ?: error("Повреждено изображение.")
        bitmap.recycle()
    }
}
