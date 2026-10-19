package com.ontop.app.backup

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.*

class TagBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card = CardEntity(1, "Заметка", createdAt = 10, updatedAt = 20, sortOrder = 0)
    private fun archive(data: ByteArray, version: Int): ByteArray {
        val manifest = JSONObject().put("format", "OnTop").put("version", version).put("createdAt", 1)
            .put("files", JSONArray().put(JSONObject().put("path", "data.json").put("bytes", data.size).put("sha256", BackupFormat.digest(data))))
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            for ((name, content) in listOf("data.json" to data, "manifest.json" to manifest.toString().toByteArray())) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        } }.toByteArray()
    }

    @Test fun legacyArchiveRestoresWithoutTopicsAndKeepsOldImportanceDefault() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val root = File(context.cacheDir, "tag-backup-${System.nanoTime()}")
        val manager = BackupManager(context, db, root = root, refreshWidgets = {})
        try {
            db.cardDao().insert(card)
            val old = db.tagDao().create("Не связывать с новым ID")
            db.tagDao().replaceForCard(1, listOf(old), emptyList())
            val json = JSONObject(String(BackupFormat.encode(BackupData(listOf(card), emptyList(), emptyList()), includeTags = false)))
            json.getJSONArray("cards").getJSONObject(0).remove("importance")
            val bytes = archive(json.toString().toByteArray(), 1)
            manager.prepare { bytes.inputStream() }
            assertNotNull(manager.state.value.ready)
            manager.restore()
            assertNull(manager.state.value.error)
            assertEquals(card, db.cardDao().findById(1))
            assertTrue(db.tagDao().all().isEmpty())
            assertTrue(db.tagDao().allLinks().isEmpty())
        } finally { manager.recover(); manager.discardPrepared(); db.close(); root.deleteRecursively() }
    }

    @Test fun duplicateOrOrphanTopicsAndMissingVersionTwoArraysNeverReplaceData() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val root = File(context.cacheDir, "tag-invalid-${System.nanoTime()}")
        val manager = BackupManager(context, db, root = root, refreshWidgets = {})
        try {
            db.cardDao().insert(card)
            val tag = db.tagDao().create("Мои мысли")
            db.tagDao().replaceForCard(1, listOf(tag), emptyList())
            val original = manager.snapshot()
            val valid = BackupFormat.encode(original)
            val invalid = mutableListOf<ByteArray>()
            invalid += JSONObject(String(valid)).apply {
                getJSONArray("tags").put(JSONObject().put("id", 999).put("name", "  МОИ   МЫСЛИ ").put("createdAt", 1))
            }.toString().toByteArray()
            invalid += JSONObject(String(valid)).apply { getJSONArray("cardTags").getJSONObject(0).put("tagId", 999) }.toString().toByteArray()
            invalid += JSONObject(String(valid)).apply { remove("tags"); remove("cardTags") }.toString().toByteArray()
            for (data in invalid) {
                val bytes = archive(data, 2)
                manager.prepare { bytes.inputStream() }
                assertNotNull(manager.state.value.error)
                assertNull(manager.state.value.ready)
                assertEquals(original, manager.snapshot())
            }
        } finally { manager.recover(); manager.discardPrepared(); db.close(); root.deleteRecursively() }
    }

    @Test fun recoveryUnderstandsJournalFingerprintWrittenBeforeTopicsExisted() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val root = File(context.cacheDir, "tag-legacy-journal-${System.nanoTime()}")
        var refreshed = 0
        val manager = BackupManager(context, db, root = root, refreshWidgets = { refreshed++ },
            checkpoint = { if (it == "committed") throw InterruptedRestore() })
        try {
            val bytes = archive(BackupFormat.encode(BackupData(listOf(card), emptyList(), emptyList()), includeTags = false), 1)
            manager.prepare { bytes.inputStream() }
            try { manager.restore(); fail("Expected interruption") } catch (_: InterruptedRestore) { }
            val journal = File(root, "restore.json")
            val record = JSONObject(journal.readText()).apply {
                remove("fingerprintVersion")
                put("fingerprint", BackupFormat.fingerprint(manager.snapshot(), includeTags = false))
            }
            journal.writeText(record.toString())
            manager.recover()
            assertEquals(1, refreshed)
            assertEquals(card, db.cardDao().findById(1))
            assertFalse(manager.state.value.recoveryRequired)
        } finally { manager.recover(); manager.discardPrepared(); db.close(); root.deleteRecursively() }
    }
    private class InterruptedRestore : Error()
}
