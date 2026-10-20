package com.ontop.app.backup

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.reminders.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.ontop.app.BoardViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.zip.*

class BackupTest {
    private class Delivery : ReminderDelivery {
        val alarms = mutableMapOf<Pair<Long, Boolean>, Long>()
        val cancelled = mutableListOf<Long>()
        var fail = false
        override fun schedule(id: Long, snooze: Boolean, at: Long) {
            if (fail) throw IOException("Injected alarm failure")
            alarms[id to snooze] = at
        }
        override fun cancel(id: Long) { cancelled.add(id); alarms.keys.removeAll { it.first == id } }
        override fun show(rule: ReminderEntity, card: CardEntity) = Unit
    }
    private class Fixture(private val fileBacked: Boolean = false) : Closeable {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "backup-test-${UUID.randomUUID()}.db"
        private fun openDatabase() = if (fileBacked) Room.databaseBuilder(app, OnTopDatabase::class.java, databaseName).build()
            else Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        var db = openDatabase()
        fun reopen() { db.close(); db = openDatabase() }
        val root = File(app.cacheDir, "backup-test-${UUID.randomUUID()}")
        val delivery = Delivery()
        val reminders get() = ReminderManager(db, delivery)
        var widgets = 0
        var failWidgets = false
        val files = mutableSetOf<String>()
        fun image(): String {
            val path = "images/${UUID.randomUUID()}.jpg"; files.add(path)
            val file = CardImageStore(app).resolve(path); file.parentFile!!.mkdirs()
            Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (files.size % 2 == 0) android.graphics.Color.BLUE else android.graphics.Color.GREEN)
                file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
            }
            return path
        }
        fun manager(checkpoint: (String) -> Unit = {}) = BackupManager(app, db, reminders, root, {
            if (failWidgets) throw IOException("Injected widget failure")
            widgets++
        }, checkpoint)
        suspend fun source(): BackupData {
            val a = image(); val b = image()
            val document = RichDocument("До\nМежду\nПосле", listOf(RichRange(0, 2, RichKind.BOLD)), listOf(
                NoteMedia("a", 3, MediaKind.IMAGE, path = a), NoteMedia("b", 9, MediaKind.IMAGE, path = b),
                NoteMedia("v", 9, MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtu.be/M7lc1UVf-VE?t=87"))))
            val cards = listOf(CardEntity(1, "Фото и видео", document.text, CardSize.WIDE, 100, 200, 7, a, RichTextCodec.encode(document)),
                CardEntity(2, "Без медиа", "  Пробелы\n\n", CardSize.NORMAL, 101, 201, -2))
            val comments = listOf(CardCommentEntity(10, 1, "Мысль", 300), CardCommentEntity(11, 2, "Ещё мысль", 301))
            val rules = listOf(ReminderEntity(20, 1, 9, 0, true, 100, 200, nextAt = 999, snoozeAt = 998,
                daysMask = 21, times = "540,1080", quietStart = 1380, quietEnd = 420, pausedUntil = System.currentTimeMillis() + 86400000),
                ReminderEntity(21, 2, 20, 0, false, 100, 200, mode = "INTERVAL", intervalHours = 3, windowStart = 1200, windowEnd = 300))
            cards.forEach { db.cardDao().insert(it) }; comments.forEach { db.cardCommentDao().insert(it) }; rules.forEach { db.reminderDao().insert(it) }
            val tags = listOf(TagEntity(1, "Работа", createdAt = 100), TagEntity(2, "Личные мысли", createdAt = 101))
            val links = listOf(CardTagCrossRef(1, 1), CardTagCrossRef(1, 2), CardTagCrossRef(2, 2))
            tags.forEach { db.tagDao().insert(it) }; links.forEach { db.tagDao().insertLink(it) }
            val events = NoteInteractionType.entries.mapIndexed { index, type ->
                NoteInteractionEntity(index + 1L, 1, type.name, 400 + index.toLong())
            } + NoteInteractionEntity(6, 2, "NOTE_OPENED", 500)
            events.forEach { db.noteInteractionDao().insert(it) }
            return BackupData(cards, comments, rules, tags, links, events)
        }
        suspend fun old(): BackupData {
            db.cardDao().deleteAll()
            db.tagDao().deleteAll()
            val old = CardEntity(99, "Прежняя заметка", "Не терять", createdAt = 1, updatedAt = 1, sortOrder = 0, imagePath = image())
            db.cardDao().insert(old)
            db.reminderDao().insert(ReminderEntity(99, 99, 12, 30, true, 1, 1, nextAt = System.currentTimeMillis() + 60_000))
            delivery.alarms[99L to false] = System.currentTimeMillis() + 60_000
            return manager().snapshot()
        }
        fun archive(data: BackupData): ByteArray = ByteArrayOutputStream().also { output ->
            BackupFormat.write(data, { CardImageStore(app).resolve(it) }, output)
        }.toByteArray()
        override fun close() { runBlocking {
            delivery.fail = false; failWidgets = false
            val manager = manager(); manager.recover(); manager.discardPrepared()
            files.addAll(manager.snapshot().paths())
            files.forEach { CardImageStore(app).resolve(it).delete() }
            db.close(); if (fileBacked) app.deleteDatabase(databaseName)
            root.deleteRecursively()
        } }
    }
    private class ProcessDeath : Error()

    @Test fun nullableProgressRoundTripsWithAllDataAndOlderDocumentsDefaultToNull() = runBlocking {
        Fixture().use { f ->
            val original = f.source()
            val values = listOf<Int?>(null, 0, 50, 100)
            val cards = values.mapIndexed { index, progress ->
                (original.cards.getOrNull(index) ?: original.cards[1].copy(id = index + 1L))
                    .copy(progress = progress, importance = index * 25,
                        status = if (index == 0) CardStatus.ARCHIVED else CardStatus.ACTIVE)
            }
            val data = original.copy(cards = cards)
            val json = JSONObject(BackupFormat.encode(data).toString(Charsets.UTF_8))
            assertTrue(json.getJSONArray("cards").getJSONObject(0).isNull("progress"))
            assertEquals(data, BackupFormat.decode(BackupFormat.encode(data)))
            val old = JSONObject(json.toString())
            repeat(values.size) { old.getJSONArray("cards").getJSONObject(it).remove("progress") }
            assertTrue(BackupFormat.decode(old.toString().toByteArray()).cards.all { it.progress == null })
            for (invalid in listOf(-1, 101, 1.5, "50", true)) {
                val broken = JSONObject(json.toString())
                broken.getJSONArray("cards").getJSONObject(0).put("progress", invalid)
                assertThrows(IllegalArgumentException::class.java) { BackupFormat.decode(broken.toString().toByteArray()) }
            }
            val archive = f.archive(data)
            f.old()
            val manager = f.manager()
            manager.prepare { archive.inputStream() }; manager.restore()
            assertNull(manager.state.value.error)
            val restored = manager.snapshot()
            assertEquals(values, restored.cards.sortedBy { it.id }.map { it.progress })
            assertEquals(cards.map { it.importance }, restored.cards.sortedBy { it.id }.map { it.importance })
            assertEquals(original.comments, restored.comments)
            assertEquals(original.tags, restored.tags)
            assertEquals(original.cardTags, restored.cardTags)
            assertEquals(original.interactions, restored.interactions)
            assertEquals(CardStatus.ARCHIVED, restored.cards.first { it.id == 1L }.status)
            val document = BackupFormat.document(restored.cards[0].description, restored.cards[0].formatting)
            assertEquals(3, document.media.size)
            document.imagePaths().forEach { assertTrue(CardImageStore(f.app).resolve(it).exists()) }
        }
    }

    @Test fun journalsFromBeforeStatusFieldRecoverAfterUpgradeWithoutDeletingCommittedMedia() = runBlocking {
        for (version in listOf(1, 2, 3, 4)) Fixture(fileBacked = true).use { f ->
            val source = f.source(); val archive = f.archive(source); val old = f.old()
            val manager = f.manager { if (it == "committed") throw ProcessDeath() }
            manager.prepare { archive.inputStream() }
            try { manager.restore(); fail("Expected interruption") } catch (_: ProcessDeath) { }
            val current = manager.snapshot()
            val journal = File(f.root, "restore.json")
            val record = JSONObject(journal.readText())
            record.put("fingerprintVersion", version).put("fingerprint",
                BackupFormat.fingerprint(current, includeTags = version >= 2, includeStatus = version >= 3, includeProgress = version >= 4, includeInteractions = false))
            journal.writeText(record.toString())
            f.reopen()
            val restarted = f.manager(); restarted.recover()
            assertNull(restarted.state.value.error)
            assertEquals(current.cards, restarted.snapshot().cards)
            current.paths().forEach { assertTrue(CardImageStore(f.app).resolve(it).exists()) }
            assertFalse(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).exists())
            assertFalse(journal.exists())
            assertEquals(1, f.delivery.alarms.size)
        }
    }

    @Test fun archiveStatusRoundTripExcludesTrashAndItsRelationsAndRestoresOldArchivesAsActive() = runBlocking {
        Fixture().use { f ->
            val source = f.source()
            f.db.cardDao().update(source.cards[0].copy(status = CardStatus.ARCHIVED))
            f.db.cardDao().update(source.cards[1].copy(status = CardStatus.TRASHED))
            val before = f.manager().snapshot()
            val archive = f.archive(before)
            val oldJson = JSONObject(BackupFormat.encode(source).toString(Charsets.UTF_8))
            val oldCards = oldJson.getJSONArray("cards")
            repeat(oldCards.length()) { oldCards.getJSONObject(it).remove("status") }
            assertTrue(BackupFormat.decode(oldJson.toString().toByteArray()).cards.all { it.status == CardStatus.ACTIVE })
            f.old()
            val manager = f.manager()
            manager.prepare { archive.inputStream() }; manager.restore()
            assertNull(manager.state.value.error)
            val restored = manager.snapshot()
            assertEquals(1, restored.cards.size)
            assertEquals(CardStatus.ARCHIVED, restored.cards.single().status)
            assertTrue(restored.comments.all { it.cardId == 1L })
            assertTrue(restored.reminders.all { it.cardId == 1L })
            assertTrue(restored.cardTags.all { it.cardId == 1L })
            assertTrue(f.delivery.alarms.isEmpty())
            assertTrue(restored.reminders.single().enabled)
            assertNull(restored.reminders.single().nextAt)
        }
    }

    @Test fun convertedVideosAndInlineOnlyImagesRestoreWithoutCreatingCover() = runBlocking {
        Fixture().use { f ->
            val source = f.source()
            val url = "https://youtube.com/live/M7lc1UVf-VE?si=original&t=12s"
            val text = "До 🧠\n$url\nПосле"
            val image = source.cards.first().imagePath!!
            val document = RichDocument(text, media = listOf(NoteMedia("shared-image", text.length,
                MediaKind.IMAGE, path = image))).convertYouTubeLinks()
            f.db.cardDao().update(source.cards.first().copy(imagePath = null, description = document.text,
                formatting = RichTextCodec.encode(document)))
            val archive = f.archive(f.manager().snapshot())
            f.old()
            val manager = f.manager()
            manager.prepare { archive.inputStream() }; manager.restore()
            assertNull(manager.state.value.error)
            val card = manager.snapshot().cards.single { it.id == 1L }
            assertNull(card.imagePath)
            val restored = RichTextCodec.decode(card.description, card.formatting)
            assertEquals(listOf(MediaKind.YOUTUBE, MediaKind.IMAGE), restored.media.map { it.kind })
            assertEquals(url, restored.media.first().video!!.source)
            assertEquals(text, restored.videoToLink(restored.media.first().id).text)
            assertArrayEquals(CardImageStore(f.app).resolve(image).readBytes(),
                CardImageStore(f.app).resolve(restored.imagePaths().single()).readBytes())
        }
    }

    @Test fun portableRoundTripRestoresDocumentsCommentsImagesAndAdvancedRules() = runBlocking {
        Fixture().use { f ->
            val source = f.source(); val archive = f.archive(source)
            val old = f.old(); val manager = f.manager()
            manager.prepare { archive.inputStream() }
            assertNotNull(manager.state.value.ready)
            assertEquals(old, manager.snapshot()) // Validation and confirmation never replace data.
            assertTrue(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).exists())
            manager.restore()
            assertNull(manager.state.value.error)
            val restored = manager.snapshot()
            assertEquals(source.comments, restored.comments)
            assertEquals(source.tags, restored.tags)
            assertEquals(source.cardTags, restored.cardTags)
            assertEquals(source.interactions, restored.interactions)
            source.cards.forEach { original ->
                val card = restored.cards.single { it.id == original.id }
                assertEquals(original, card.copy(imagePath = original.imagePath, formatting = original.formatting))
                val before = BackupFormat.document(original.description, original.formatting)
                val after = BackupFormat.document(card.description, card.formatting)
                assertEquals(before.text, after.text); assertEquals(before.ranges, after.ranges)
                assertEquals(before.media, after.media.mapIndexed { i, block -> block.copy(path = before.media[i].path) })
                before.media.forEachIndexed { index, block -> block.path?.let { path ->
                    assertArrayEquals(CardImageStore(f.app).resolve(path).readBytes(),
                        CardImageStore(f.app).resolve(after.media[index].path!!).readBytes())
                } }
            }
            assertEquals(restored.cards.first { it.id == 1L }.imagePath, BackupFormat.document(restored.cards.first { it.id == 1L }.description,
                restored.cards.first { it.id == 1L }.formatting).media.first().path)
            restored.paths().forEach { path -> assertTrue(CardImageStore(f.app).resolve(path).exists()); assertTrue(BackupFormat.validPath(path)) }
            assertEquals(2, restored.paths().size)
            restored.reminders.forEach { rule ->
                val original = source.reminders.single { it.cardId == rule.cardId }
                assertNotEquals(original.id, rule.id); assertNotEquals(99L, rule.id)
                assertEquals(original.copy(id = rule.id, nextAt = rule.nextAt, snoozeAt = null), rule)
            }
            assertFalse(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).exists())
            assertTrue(99L in f.delivery.cancelled); assertFalse(f.delivery.alarms.keys.any { it.first == 99L })
            assertEquals(1, f.delivery.alarms.size); assertEquals(1, f.widgets)
            manager.recover(); assertEquals(1, f.widgets); assertEquals(1, f.delivery.alarms.size)
            assertNotEquals("initial", manager.revision.value)
            assertEquals(manager.revision.value, f.manager().revision.value)
            val contents = unzip(archive)
            assertEquals(setOf("manifest.json", "data.json") + source.paths(), contents.keys)
            assertFalse(contents.getValue("data.json").toString(Charsets.UTF_8).contains(f.app.filesDir.path))
            assertTrue(contents.getValue("data.json").toString(Charsets.UTF_8).contains("M7lc1UVf-VE"))
        }
    }

    @Test fun interruptionBeforeOrAfterCommitRecoversWithoutDanglingReferences() = runBlocking {
        for (phase in listOf("journal", "image", "files", "transaction", "committed", "reconciled", "generation")) Fixture(fileBacked = true).use { f ->
            val source = f.source(); val archive = f.archive(source); val old = f.old()
            val manager = f.manager { if (it == phase) throw ProcessDeath() }
            manager.prepare { archive.inputStream() }
            try { manager.restore(); fail("Expected process death at $phase") } catch (_: ProcessDeath) { }
            f.reopen()
            val committed = phase in listOf("committed", "reconciled", "generation")
            if (!committed) assertEquals(old, f.manager().snapshot())
            if (phase !in listOf("reconciled", "generation")) assertTrue(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).exists())
            val restarted = f.manager(); restarted.recover()
            assertNull(restarted.state.value.error)
            val current = restarted.snapshot()
            assertEquals(if (committed) setOf(1L, 2L) else setOf(99L), current.cards.map { it.id }.toSet())
            current.paths().forEach { assertTrue(CardImageStore(f.app).resolve(it).isFile) }
            assertFalse(File(f.root, "restore.json").exists())
            BackupManager.dataAccess { assertTrue(current.cards.isNotEmpty()) }
            restarted.recover() // Repeat is harmless.
        }
    }

    @Test fun postCommitAlarmOrWidgetFailureKeepsJournalAndOldImagesUntilRetry() = runBlocking {
        for (failure in listOf("alarms", "widgets")) Fixture().use { f ->
            val source = f.source(); val archive = f.archive(source); val old = f.old(); val manager = f.manager()
            manager.prepare { archive.inputStream() }
            if (failure == "alarms") f.delivery.fail = true else f.failWidgets = true
            manager.restore()
            assertTrue(manager.state.value.recoveryRequired)
            assertNotNull(manager.state.value.error)
            assertEquals(setOf(1L, 2L), manager.snapshot().cards.map { it.id }.toSet())
            assertTrue(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).isFile)
            f.delivery.fail = false; f.failWidgets = false
            val restarted = f.manager(); restarted.recover()
            assertFalse(restarted.state.value.recoveryRequired)
            assertFalse(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).exists())
            assertEquals(1, f.delivery.alarms.size)
        }
    }

    @Test fun fileFailureBeforeCommitAndExportFailureDoNotReplaceCurrentData() = runBlocking {
        Fixture().use { f ->
            val source = f.source(); val archive = f.archive(source); val old = f.old()
            val manager = f.manager { if (it == "files") throw IOException("No space") }
            manager.prepare { archive.inputStream() }; manager.restore()
            assertEquals(old, manager.snapshot()); assertTrue(manager.state.value.recoveryRequired)
            f.manager().recover(); assertEquals(old, manager.snapshot())
            val outputFailure = f.manager()
            outputFailure.create { object : OutputStream() { override fun write(b: Int) { throw IOException("Provider failed") } } }
            assertNotNull(outputFailure.state.value.error); assertEquals(old, outputFailure.snapshot())
            assertFalse(outputFailure.state.value.recoveryRequired)
        }
    }

    @Test fun invalidArchivesNeverTouchExistingData() = runBlocking {
        Fixture().use { f ->
            val source = f.source(); val valid = unzip(f.archive(source)); val old = f.old(); val manager = f.manager()
            val invalid = mutableListOf<ByteArray>()
            invalid += "not a zip".toByteArray()
            invalid += zip(valid).copyOf(30)
            invalid += zip(valid + ("../outside.jpg" to byteArrayOf(1)))
            invalid += zip(valid + ("/absolute.jpg" to byteArrayOf(1)))
            invalid += zip(valid + ("images\\escape.jpg" to byteArrayOf(1)))
            invalid += zip(valid - source.paths().first())
            invalid += zip(valid + ("data.json" to "{}".toByteArray()))
            invalid += zip(valid + ("manifest.json" to JSONObject(valid.getValue("manifest.json").toString(Charsets.UTF_8)).put("version", 99).toString().toByteArray()))
            val badData = JSONObject(valid.getValue("data.json").toString(Charsets.UTF_8))
            badData.getJSONArray("cards").getJSONObject(0).remove("title")
            invalid += withData(valid, badData.toString().toByteArray())
            val orphan = JSONObject(valid.getValue("data.json").toString(Charsets.UTF_8))
            orphan.getJSONArray("comments").getJSONObject(0).put("cardId", 700)
            invalid += withData(valid, orphan.toString().toByteArray())
            val malformed = JSONObject(valid.getValue("data.json").toString(Charsets.UTF_8))
            malformed.getJSONArray("cards").getJSONObject(0).put("formatting", "{\"v\":2,\"ranges\":[],\"media\":[{\"id\":\"x\",\"at\":999999,\"kind\":\"IMAGE\",\"path\":\"images/a.jpg\"}]}")
            invalid += withData(valid, malformed.toString().toByteArray())
            val duplicate = JSONObject(valid.getValue("data.json").toString(Charsets.UTF_8))
            duplicate.getJSONArray("cards").getJSONObject(1).put("id", 1)
            invalid += withData(valid, duplicate.toString().toByteArray())
            val badRule = JSONObject(valid.getValue("data.json").toString(Charsets.UTF_8))
            badRule.getJSONArray("reminders").getJSONObject(0).put("daysMask", 0)
            invalid += withData(valid, badRule.toString().toByteArray())
            val badKey = JSONObject(valid.getValue("data.json").toString(Charsets.UTF_8))
            val formatting = JSONObject(badKey.getJSONArray("cards").getJSONObject(0).getString("formatting"))
            formatting.getJSONArray("media").getJSONObject(0).put("id", "body")
            badKey.getJSONArray("cards").getJSONObject(0).put("formatting", formatting.toString())
            invalid += withData(valid, badKey.toString().toByteArray())
            invalid += withData(valid, ("{\"nested\":" + "[".repeat(500) + "0" + "]".repeat(500) + "}").toByteArray())
            invalid += withData(valid, valid.getValue("data.json") + " {}".toByteArray())
            for (bytes in invalid) {
                manager.prepare { bytes.inputStream() }
                assertNotNull(manager.state.value.error); assertNull(manager.state.value.ready)
                assertEquals(old, manager.snapshot())
                assertTrue(CardImageStore(f.app).resolve(old.cards.single().imagePath!!).exists())
                assertEquals(0, f.widgets)
            }
        }
    }

    @Test fun decompressionBudgetsDuplicateNamesAndCrcAreEnforced() {
        Fixture().use { f ->
            fun rejected(bytes: ByteArray, limits: BackupLimits = BackupLimits()) {
                val folder = File(f.root, UUID.randomUUID().toString()).apply { mkdirs() }
                val archive = File(folder, "input.zip").apply { writeBytes(bytes) }
                try { BackupFormat.read(archive, File(folder, "out"), limits); fail("Invalid ZIP accepted") } catch (expected: Exception) { }
            }
            val bomb = zip(mapOf("manifest.json" to "{}".toByteArray(), "data.json" to ByteArray(100_000)))
            rejected(bomb, BackupLimits(unpacked = 1000))
            rejected(bomb, BackupLimits(archive = 10))
            rejected(bomb, BackupLimits(json = 500))
            rejected(zip(mapOf("manifest.json" to byteArrayOf(), "data.json" to byteArrayOf(), "images/a.jpg" to byteArrayOf())), BackupLimits(entries = 2))
            val duplicated = zip(mapOf("images/a.jpg" to byteArrayOf(), "images/b.jpg" to byteArrayOf()))
            val name = "images/b.jpg".toByteArray()
            for (i in 0..duplicated.size - name.size) if (name.indices.all { duplicated[i + it] == name[it] }) duplicated[i + 7] = 'a'.code.toByte()
            rejected(duplicated)
            val stored = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                val data = "unique-payload".toByteArray()
                zip.putNextEntry(ZipEntry("data.json").apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; crc = CRC32().apply { update(data) }.value })
                zip.write(data); zip.closeEntry()
            } }.toByteArray()
            val payload = "unique-payload".toByteArray()
            val start = (0..stored.size - payload.size).first { i -> payload.indices.all { stored[i + it] == payload[it] } }
            stored[start] = 'X'.code.toByte(); rejected(stored)
        }
    }

    @Test fun emptyBackupCanReplaceAndRecoverAnEmptyDataset() = runBlocking {
        Fixture().use { f ->
            val manager = f.manager(); val output = ByteArrayOutputStream()
            manager.create { output }; assertNull(manager.state.value.error)
            manager.prepare { output.toByteArray().inputStream() }; manager.restore()
            assertNull(manager.state.value.error); assertTrue(manager.snapshot().cards.isEmpty())
            assertEquals(1, f.widgets)
        }
    }

    @Test fun replacementResetsAnExistingImageEditorSession() = runBlocking {
        Fixture().use { f ->
            val archive = f.archive(f.source()); val old = f.old(); val manager = f.manager()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var model: BoardViewModel
            instrumentation.runOnMainSync {
                model = BoardViewModel(f.app.applicationContext as android.app.Application, f.db, manager)
                model.beginImageEdit(old.cards.single())
            }
            try {
                assertEquals(old.cards.single().imagePath, model.state.value.draftImagePath)
                manager.prepare { archive.inputStream() }; manager.restore()
                withTimeout(5_000) { model.state.first { it.draftImagePath == null } }
                val restored = manager.snapshot().cards.first { it.id == 1L }
                instrumentation.runOnMainSync { model.beginImageEdit(restored) }
                assertEquals(restored.imagePath, model.state.value.draftImagePath)
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun clearedWidgetChoiceDoesNotReuseACoincidingCardId() = runBlocking {
        Fixture().use { f ->
            f.source()
            val name = "backup-widget-test-${UUID.randomUUID()}"
            val selections = com.ontop.app.widget.WidgetSelectionStore(f.app, name)
            try {
                selections.set(1234, 1)
                val before = com.ontop.app.widget.CardWidget().load(f.app, 1234, f.db, selections)
                assertEquals(1L, before.cardId); before.image?.recycle()
                selections.clear()
                val after = com.ontop.app.widget.CardWidget().load(f.app, 1234, f.db, selections)
                assertNull(after.cardId); assertEquals("Выберите карточку", after.title)
            } finally { f.app.deleteSharedPreferences(name) }
        }
    }

    @Test fun legacyVersionFourHasNoSyntheticHistoryAndInvalidHistoryCannotReplaceData() = runBlocking {
        Fixture().use { f ->
            val source = f.source()
            val files = unzip(f.archive(source)).toMutableMap()
            val data = JSONObject(files.getValue("data.json").toString(Charsets.UTF_8)).apply { remove("interactions") }
            files["manifest.json"] = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
                .put("version", 4).toString().toByteArray()
            val archive = withData(files, data.toString().toByteArray())
            f.old()
            val manager = f.manager()
            manager.prepare { archive.inputStream() }; manager.restore()
            assertNull(manager.state.value.error)
            assertTrue(manager.snapshot().interactions.isEmpty())
            val before = manager.snapshot()
            val malformed = JSONObject(BackupFormat.encode(source).toString(Charsets.UTF_8))
            malformed.getJSONArray("interactions").getJSONObject(0).put("noteId", 99999)
            val invalid = withData(unzip(f.archive(source)), malformed.toString().toByteArray())
            manager.prepare { invalid.inputStream() }
            assertNotNull(manager.state.value.error)
            assertEquals(before, manager.snapshot())
            assertTrue(BackupFormat.decode(BackupFormat.encode(source, includeInteractions = false)).interactions.isEmpty())
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry()
        } }
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun withData(files: Map<String, ByteArray>, bytes: ByteArray): ByteArray {
        val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        val list = manifest.getJSONArray("files")
        (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("path") == "data.json" }
            .put("bytes", bytes.size).put("sha256", BackupFormat.digest(bytes))
        return zip(files + ("data.json" to bytes) + ("manifest.json" to manifest.toString().toByteArray()))
    }
}
