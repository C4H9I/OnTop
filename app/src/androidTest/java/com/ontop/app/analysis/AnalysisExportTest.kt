package com.ontop.app.analysis

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.text.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.time.Instant
import java.time.ZoneId

class AnalysisExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = Instant.parse("2026-10-19T12:00:00Z")
    private fun database() = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
    private suspend fun json(db: OnTopDatabase): String = withContext(Dispatchers.IO) {
        StringWriter().also { out -> db.withTransaction {
            AnalysisJson.write(db, out, at, "1.0", ZoneId.of("Europe/Moscow"))
        } }.toString()
    }
    private fun card(id: Long, text: String = "Обычный текст", importance: Int = 50) =
        CardEntity(id, "Мысль $id", text, createdAt = 0, updatedAt = 1000, sortOrder = id, importance = importance)

    @Test fun nullableProgressIsExplicitAndImportanceRepresentationStaysInteger() = runBlocking {
        val db = database()
        try {
            val values = listOf<Int?>(null, 0, 50, 100)
            values.forEachIndexed { index, progress -> db.cardDao().insert(card(index + 1L).copy(progress = progress)) }
            val root = JSONObject(json(db))
            assertEquals(3, root.getInt("schemaVersion"))
            val notes = root.getJSONArray("notes")
            assertTrue(notes.getJSONObject(0).has("progress"))
            assertTrue(notes.getJSONObject(0).isNull("progress"))
            for (index in 1..3) assertEquals(values[index], notes.getJSONObject(index).getInt("progress"))
            repeat(4) { assertEquals(50, notes.getJSONObject(it).getInt("importance")) }
        } finally { db.close() }
    }

    @Test fun convertedSharedMultimediaExportsOriginalUrlsAndDocumentOrderWithoutCoverOrPrivatePaths() = runBlocking {
        val db = database()
        try {
            val url = "https://youtube.com/shorts/M7lc1UVf-VE?si=original"
            val text = "До\n$url\nПосле"
            val rich = RichDocument(text, media = listOf(NoteMedia("shared-image", text.length,
                MediaKind.IMAGE, path = "images/abc.jpg"))).convertYouTubeLinks()
            db.cardDao().insert(card(1, rich.text).copy(formatting = RichTextCodec.encode(rich)))
            val encoded = json(db)
            val note = JSONObject(encoded).getJSONArray("notes").getJSONObject(0)
            assertEquals("До\n\nПосле", note.getString("content"))
            val blocks = note.getJSONArray("documentBlocks")
            assertEquals(listOf("text", "youtube", "text", "image"),
                (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
            assertTrue(encoded.contains(url))
            assertFalse(encoded.contains("images/"))
            assertEquals(2, note.getJSONArray("media").length())
        } finally { db.close() }
    }

    @Test fun singleNoteProducesReviewableRealExample() = runBlocking {
        val db = database()
        try {
            db.cardDao().insert(card(7, "Замечать хорошее.\nЗаписывать наблюдения."))
            val encoded = json(db)
            assertEquals(1, JSONObject(encoded).getJSONArray("notes").length())
            File(context.getExternalFilesDir(null), "ontop-analysis-example.json").writeText(encoded)
        } finally { db.close() }
    }

    @Test fun emptyAndPlainNotesUseStableStringIdsIsoDatesAndRealFieldsOnly() = runBlocking {
        val db = database()
        try {
            val empty = JSONObject(json(db))
            assertEquals(3, empty.getInt("schemaVersion"))
            assertEquals(at.toString(), empty.getString("exportedAt"))
            assertEquals(0, empty.getJSONArray("notes").length())
            listOf(0, 50, 100).forEachIndexed { i, importance -> db.cardDao().insert(card(i + 1L, importance = importance)) }
            val notes = JSONObject(json(db)).getJSONArray("notes")
            assertEquals(3, notes.length())
            repeat(3) { i ->
                val note = notes.getJSONObject(i)
                assertEquals((i + 1).toString(), note.getString("id"))
                assertEquals(listOf(0, 50, 100)[i], note.getInt("importance"))
                assertEquals("Обычный текст", note.getString("content"))
                assertEquals("1970-01-01T00:00:00Z", note.getString("createdAt"))
                assertEquals("1970-01-01T00:00:01Z", note.getString("updatedAt"))
                assertEquals("ACTIVE", note.getString("status")); assertFalse(note.has("formatting"))
                assertEquals(0, note.getJSONArray("reminders").length())
            }
        } finally { db.close() }
    }

    @Test fun formattedUnicodeMediaCommentsTopicsAndSchedulesKeepOrderWithoutPaths() = runBlocking {
        val db = database()
        try {
            val text = "Первый абзац 🧠\nСередина\nПоследний абзац \"цитата\"\t雪"
            val document = RichDocument(text, listOf(RichRange(0, 6, RichKind.BOLD)), listOf(
                NoteMedia("original-image", 16, MediaKind.IMAGE, path = "images/abc.jpg"),
                NoteMedia("original-video", 25, MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?t=42")),
                NoteMedia("second-image", 25, MediaKind.IMAGE, path = "images/def.jpg")))
            db.cardDao().insert(card(42, text, 100).copy(imagePath = "/data/user/0/private/cover.jpg", formatting = RichTextCodec.encode(document)))
            val a = db.tagDao().create("Размышления")
            val b = db.tagDao().create("Идеи")
            db.tagDao().replaceForCard(42, listOf(a, a, b), emptyList())
            // Reverse insertion and equal dates exercise chronological pagination and tie breaking.
            repeat(131) { db.cardCommentDao().insert(CardCommentEntity(id = it + 1L, cardId = 42,
                text = "Комментарий $it", createdAt = (130 - it).toLong() / 2)) }
            db.reminderDao().insert(ReminderEntity(cardId = 42, hour = 9, minute = 5, enabled = false,
                createdAt = 0, updatedAt = 1000, times = "545,1080", daysMask = 5, quietStart = 1380, quietEnd = 420,
                pausedUntil = 2000, nextAt = 3000, snoozeAt = 4000))
            val encoded = json(db)
            val note = JSONObject(encoded).getJSONArray("notes").getJSONObject(0)
            assertEquals(text, note.getString("content"))
            val blocks = note.getJSONArray("documentBlocks")
            val reconstructed = buildString { repeat(blocks.length()) { i -> val block = blocks.getJSONObject(i)
                assertEquals(i, block.getInt("index"))
                if (block.getString("type") == "text") append(block.getString("text")) } }
            assertEquals(text, reconstructed)
            val media = note.getJSONArray("media")
            assertEquals(4, media.length())
            assertEquals("cover", media.getJSONObject(0).getString("role"))
            repeat(3) { i -> val item = media.getJSONObject(i + 1)
                assertEquals(document.media[i].offset, item.getInt("sourceTextOffsetUtf16"))
                assertEquals(item.getString("id"), blocks.getJSONObject(item.getInt("documentBlockIndex")).getString("mediaId")) }
            assertEquals("dQw4w9WgXcQ", media.getJSONObject(2).getString("videoId"))
            assertEquals(42, media.getJSONObject(2).getInt("startSeconds"))
            assertFalse(encoded.contains("/data/")); assertFalse(encoded.contains("images/"))
            assertFalse(encoded.contains("original-image")); assertFalse(encoded.contains("base64", true))
            assertEquals(2, note.getJSONArray("tags").length())
            val comments = note.getJSONArray("comments")
            assertEquals(131, comments.length())
            val expected = db.cardCommentDao().all().sortedWith(compareBy({ it.createdAt }, { it.id }))
            repeat(131) { assertEquals(expected[it].text, comments.getJSONObject(it).getString("text")) }
            val reminder = note.getJSONArray("reminders").getJSONObject(0)
            assertEquals("TIMES", reminder.getString("scheduleType"))
            assertEquals("09:05", reminder.getJSONArray("times").getString(0))
            assertEquals("MONDAY", reminder.getJSONArray("weekdays").getString(0))
            assertEquals("WEDNESDAY", reminder.getJSONArray("weekdays").getString(1))
            assertFalse(reminder.getBoolean("enabled")); assertFalse(reminder.has("id"))
            assertEquals("Europe/Moscow", reminder.getString("timeZoneAtExport"))
            assertEquals("23:00", reminder.getJSONObject("quietHours").getString("start"))
            assertEquals(Instant.ofEpochMilli(2000).toString(), reminder.getString("pausedUntil"))
            // Save a real deterministic output for review and a second, independent JSON parser.
            File(context.getExternalFilesDir(null), "ontop-analysis-test.json").writeText(encoded)
        } finally { db.close() }
    }

    @Test fun intervalAndMigratedDailyRulesKeepActualScheduleParameters() = runBlocking {
        val db = database()
        try {
            db.cardDao().insert(card(1)); db.cardDao().insert(card(2))
            db.reminderDao().insert(ReminderEntity(cardId = 1, hour = 7, minute = 15, enabled = true, createdAt = 0, updatedAt = 1))
            db.reminderDao().insert(ReminderEntity(cardId = 2, hour = 9, minute = 0, enabled = true, createdAt = 0, updatedAt = 1,
                mode = "INTERVAL", intervalHours = 4, windowStart = 1320, windowEnd = 360, daysMask = 64))
            val notes = JSONObject(json(db)).getJSONArray("notes")
            assertEquals("07:15", notes.getJSONObject(0).getJSONArray("reminders").getJSONObject(0).getJSONArray("times").getString(0))
            val interval = notes.getJSONObject(1).getJSONArray("reminders").getJSONObject(0)
            assertEquals("INTERVAL", interval.getString("scheduleType"))
            assertEquals(4, interval.getInt("intervalHours"))
            assertEquals("22:00", interval.getString("windowStart"))
            assertEquals("06:00", interval.getString("windowEnd"))
            assertEquals("SUNDAY", interval.getJSONArray("weekdays").getString(0))
            assertFalse(interval.has("times"))
        } finally { db.close() }
    }

    @Test fun largeTextAndManyNotesStreamValidJsonWithoutChangingDatabase() = runBlocking {
        val db = database()
        try {
            val text = "Русский текст 🧠 雪\n".repeat(2048)
            repeat(160) { db.cardDao().insert(card(it + 1L, text)) }
            db.cardDao().insert(card(161, "Большой абзац\n".repeat(32768)))
            val encoded = json(db)
            val parsed = JSONObject(encoded).getJSONArray("notes")
            assertEquals(161, parsed.length())
            assertEquals(text, parsed.getJSONObject(159).getString("content"))
            assertEquals("Большой абзац\n".repeat(32768), parsed.getJSONObject(160).getString("content"))
            assertEquals(card(1, text), db.cardDao().findById(1))
            assertTrue(encoded.toByteArray().size > 10_000_000)
        } finally { db.close() }
    }

    @Test fun cancellationAndWriteOpenCloseErrorsAreReportedAndTemporaryFilesCleaned() = runBlocking {
        val db = database()
        try {
            db.cardDao().insert(card(1))
            val exporter = AnalysisExporter(context, db)
            exporter.cancelled()
            assertEquals("Экспорт отменён", exporter.state.value.message)
            val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ontop-analysis-") }.toSet()
            exporter.export { throw IOException("provider unavailable") }
            assertNotNull(exporter.state.value.error); assertFalse(exporter.state.value.busy)
            for (onClose in listOf(false, true)) {
                exporter.export { object : OutputStream() {
                    override fun write(value: Int) { if (!onClose) throw IOException("disk full") }
                    override fun close() { if (onClose) throw IOException("close failed") }
                } }
                assertNotNull(exporter.state.value.error); assertFalse(exporter.state.value.busy)
            }
            assertEquals(before, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ontop-analysis-") }.toSet())
            val output = ByteArrayOutputStream()
            exporter.export { output }
            assertNull(exporter.state.value.error)
            assertEquals(1, JSONObject(output.toString("UTF-8")).getJSONArray("notes").length())
            val cancelled = launch { exporter.export { throw CancellationException("cancel") } }
            cancelled.join()
            assertFalse(exporter.state.value.busy)
            assertEquals("Экспорт отменён", exporter.state.value.message)
        } finally { db.close() }
    }

    @Test fun slowProviderDoesNotHoldDatabaseAndConcurrentExportIsIgnored() = runBlocking {
        val db = database()
        val release = CompletableDeferred<Unit>()
        try {
            db.cardDao().insert(card(1, "Snapshot"))
            val exporter = AnalysisExporter(context, db)
            val entered = CompletableDeferred<Unit>()
            val output = ByteArrayOutputStream()
            val first = launch { exporter.export {
                entered.complete(Unit)
                runBlocking { release.await() }
                output
            } }
            withTimeout(10_000) { entered.await() }
            var openedAgain = false
            exporter.export { openedAgain = true; ByteArrayOutputStream() }
            assertFalse(openedAgain)
            withTimeout(5000) { com.ontop.app.backup.BackupManager.dataAccess {
                db.cardDao().update(card(1, "New value"))
            } }
            release.complete(Unit); first.join()
            assertEquals("Snapshot", JSONObject(output.toString("UTF-8")).getJSONArray("notes").getJSONObject(0).getString("content"))
            assertEquals("New value", db.cardDao().findById(1)!!.description)
        } finally { release.complete(Unit); db.close() }
    }
}
