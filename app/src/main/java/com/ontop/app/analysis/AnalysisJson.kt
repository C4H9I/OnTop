package com.ontop.app.analysis

import android.util.JsonWriter
import com.ontop.app.data.*
import com.ontop.app.reminders.*
import com.ontop.app.text.*
import java.io.Writer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One-way analysis schema. Never emits storage paths or the internal formatting JSON. */
internal object AnalysisJson {
    const val SCHEMA_VERSION = 2
    private fun safeId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun iso(value: Long) = Instant.ofEpochMilli(value).toString()

    suspend fun write(db: OnTopDatabase, output: Writer, exportedAt: Instant,
        appVersion: String?, zone: ZoneId) {
        val json = JsonWriter(output).apply { setIndent("  ") }
        json.beginObject().name("schemaVersion").value(SCHEMA_VERSION)
            .name("exportedAt").value(exportedAt.toString())
        appVersion?.let { json.name("appVersion").value(it) }
        json.name("notes").beginArray()
        val tags = db.tagDao().all().associateBy { it.id }
        var after = Long.MIN_VALUE
        while (true) {
            val page = db.cardDao().analysisPage(after, 16)
            if (page.isEmpty()) break
            for (card in page) {
                currentCoroutineContext().ensureActive()
                val document = RichTextCodec.decode(card.description, card.formatting)
                json.beginObject().name("id").value(card.id.toString()).name("title").value(card.title)
                    .name("content").value(document.text).name("importance").value(card.importance.coerceIn(0, 100))
                    .name("status").value(card.status.name)
                    .name("progress").apply { if (card.progress == null) nullValue() else value(card.progress.toLong()) }
                    .name("tags").beginArray()
                db.tagDao().forCard(card.id).mapNotNull { tags[it]?.name }.distinct().sorted().forEach { json.value(it) }
                json.endArray().name("createdAt").value(iso(card.createdAt)).name("updatedAt").value(iso(card.updatedAt))
                json.name("comments").beginArray()
                var commentTime = Long.MIN_VALUE
                var commentId = Long.MIN_VALUE
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val comments = db.cardCommentDao().analysisPage(card.id, commentTime, commentId, 64)
                    if (comments.isEmpty()) break
                    comments.forEach { comment ->
                        json.beginObject().name("id").value(comment.id.toString()).name("text").value(comment.text)
                            .name("createdAt").value(iso(comment.createdAt)).endObject()
                    }
                    commentTime = comments.last().createdAt; commentId = comments.last().id
                }
                json.endArray()
                writeDocument(json, card, document)
                json.name("reminders").beginArray()
                db.reminderDao().forCard(card.id)?.let { writeReminder(json, it, zone) }
                json.endArray().endObject()
            }
            after = page.last().id
        }
        json.endArray().endObject()
        json.flush() // Caller owns the stream and its close errors.
    }

    private fun writeDocument(json: JsonWriter, card: CardEntity, document: RichDocument) {
        val mediaIndexes = mutableListOf<Int>()
        json.name("documentBlocks").beginArray()
        var start = 0
        var index = 0
        document.media.forEach { media ->
            if (media.offset > start) {
                json.beginObject().name("index").value(index++).name("type").value("text")
                    .name("text").value(document.text.substring(start, media.offset)).endObject()
            }
            mediaIndexes += index
            json.beginObject().name("index").value(index++).name("type").value(media.kind.name.lowercase())
                .name("mediaId").value("media-${safeId(media.id)}").endObject()
            start = media.offset
        }
        if (start < document.text.length) json.beginObject().name("index").value(index).name("type").value("text")
            .name("text").value(document.text.substring(start)).endObject()
        json.endArray().name("media").beginArray()
        card.imagePath?.let { path ->
            json.beginObject().name("id").value("cover").name("type").value("image")
                .name("role").value("cover").name("assetId").value("image-${safeId(path)}").endObject()
        }
        document.media.forEachIndexed { i, media ->
            json.beginObject().name("id").value("media-${safeId(media.id)}")
                .name("type").value(media.kind.name.lowercase()).name("role").value("inline")
                .name("documentBlockIndex").value(mediaIndexes[i]).name("sourceTextOffsetUtf16").value(media.offset)
            if (media.kind == MediaKind.IMAGE) {
                json.name("assetId").value("image-${safeId(requireNotNull(media.path))}")
            } else {
                val video = requireNotNull(media.video)
                json.name("url").value(video.source).name("watchUrl").value(video.watchUrl)
                    .name("videoId").value(video.id).name("startSeconds").value(video.seconds)
            }
            json.endObject()
        }
        json.endArray()
    }

    private fun writeReminder(json: JsonWriter, rule: ReminderEntity, zone: ZoneId) {
        val schedule = rule.schedule()
        json.beginObject().name("enabled").value(rule.enabled).name("scheduleType").value(schedule.mode.name)
            .name("timeZonePolicy").value("deviceLocal").name("timeZoneAtExport").value(zone.id)
            .name("weekdays").beginArray()
        DayOfWeek.entries.filter { schedule.daysMask and (1 shl (it.value - 1)) != 0 }.forEach { json.value(it.name) }
        json.endArray()
        if (schedule.mode == ScheduleMode.TIMES) {
            json.name("times").beginArray()
            schedule.times.sorted().forEach { json.value(minuteLabel(it)) }
            json.endArray()
        } else json.name("intervalHours").value(schedule.intervalHours)
            .name("windowStart").value(minuteLabel(schedule.windowStart)).name("windowEnd").value(minuteLabel(schedule.windowEnd))
        schedule.quietStart?.let { json.name("quietHours").beginObject().name("start").value(minuteLabel(it))
            .name("end").value(minuteLabel(requireNotNull(schedule.quietEnd))).endObject() }
        json.name("createdAt").value(iso(rule.createdAt)).name("updatedAt").value(iso(rule.updatedAt))
        rule.pausedUntil?.let { json.name("pausedUntil").value(iso(it)) }
        rule.nextAt?.let { json.name("nextAt").value(iso(it)) }
        rule.snoozeAt?.let { json.name("snoozeAt").value(iso(it)) }
        json.endObject()
    }
}
