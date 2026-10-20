package com.ontop.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.backup.*
import com.ontop.app.analysis.AnalysisJson
import com.ontop.app.sharing.sharedNoteText
import com.ontop.app.widget.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChecklistStorageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val entries = listOf(ChecklistItem("a", "Первый 🧠", true), ChecklistItem("b", "Второй"), ChecklistItem("c", "Третий"))
    private fun card() = CardEntity(1, "Список", "Описание", createdAt = 1, updatedAt = 2, sortOrder = 4,
        importance = 75, noteType = NoteType.CHECKLIST, checklistJson = Checklist.encode(entries))

    @Test fun fixedAndRotationWidgetsRenderCheckMarksWithoutRecordingViews() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val name = "checklist-widget-${System.nanoTime()}"
        val store = WidgetSelectionStore(context, name)
        try {
            db.cardDao().insert(card())
            store.set(1, 1)
            store.save(2, WidgetSelection(WidgetMode.ROTATION))
            listOf(1, 2).forEach { id ->
                val content = CardWidget().load(context, id, db, store)
                assertEquals(1L, content.cardId)
                assertTrue(content.title.contains("[x] Первый 🧠")); assertTrue(content.title.contains("[ ] Второй"))
            }
            assertTrue(db.noteInteractionDao().all().isEmpty())
        } finally { store.clear(); context.deleteSharedPreferences(name); db.close() }
    }

    @Test fun persistedOrderAndProgressStayConsistentAndManualProgressIsProtected() = runBlocking {
        val name = "checklist-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
        try {
            db.cardDao().insert(card())
            var saved = db.cardDao().findById(1)!!
            assertEquals(33, saved.progress)
            val reordered = entries.reversed()
            db.cardDao().update(saved.copy(checklistJson = Checklist.encode(reordered)))
            db.close(); db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            saved = db.cardDao().findById(1)!!
            assertEquals(reordered, saved.checklistItems()); assertEquals(33, saved.progress)
            db.cardDao().updatePercentages(1, 90, 100, 3)
            assertEquals(33, db.cardDao().findById(1)!!.progress)
            db.cardDao().update(saved.copy(checklistJson = Checklist.encode(entries.map { it.copy(done = true) })))
            val complete = db.cardDao().findById(1)!!
            assertEquals(100, complete.progress)
            val normal = CardEntity(2, "Обычная", "Текст", createdAt = 1, updatedAt = 2, sortOrder = 0, progress = 50)
            db.cardDao().insert(normal)
            assertEquals(listOf(1L, 2L), sortedBoardCards(db.cardDao().allCards(), BoardSort.PROGRESS).map { it.id })
            assertEquals(normal, db.cardDao().findById(2))
            db.cardDao().update(complete.copy(checklistJson = Checklist.encode(emptyList())))
            assertEquals(0, db.cardDao().findById(1)!!.progress)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun backupAndAnalysisPreserveIdsOrderFlagsAndReadableShareText() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val root = File(context.cacheDir, "checklist-backup-${System.nanoTime()}").apply { mkdirs() }
        try {
            db.cardDao().insert(card())
            val saved = db.cardDao().findById(1)!!
            val data = BackupData(listOf(saved), emptyList(), emptyList())
            val file = File(root, "data.ontop")
            file.outputStream().use { BackupFormat.write(data, { error("No media") }, it) }
            assertEquals(data, BackupFormat.read(file, File(root, "restored").apply { mkdirs() }))
            val manager = BackupManager(context, db, root = File(root, "manager"), refreshWidgets = {})
            db.cardDao().deleteAll()
            manager.prepare { file.inputStream() }
            assertNull(manager.state.value.error)
            manager.restore()
            assertNull(manager.state.value.error)
            assertEquals(saved, db.cardDao().findById(1))
            assertTrue(db.noteInteractionDao().all().isEmpty())
            val output = StringWriter()
            AnalysisJson.write(db, output, Instant.EPOCH, "test", ZoneId.of("UTC"))
            val json = JSONObject(output.toString())
            assertEquals(3, json.getInt("schemaVersion"))
            val note = json.getJSONArray("notes").getJSONObject(0)
            assertEquals("CHECKLIST", note.getString("noteType")); assertEquals(33, note.getInt("progress"))
            val list = note.getJSONArray("checklist")
            assertEquals("a", list.getJSONObject(0).getString("id")); assertTrue(list.getJSONObject(0).getBoolean("done"))
            assertEquals(2, list.getJSONObject(2).getInt("position"))
            assertTrue(note.getString("content").contains("[x] Первый 🧠\n[ ] Второй"))
            assertEquals("Список\n\nОписание\n\n[x] Первый 🧠\n[ ] Второй\n[ ] Третий", sharedNoteText(saved))
            val normal = saved.copy(noteType = NoteType.NOTE, checklistJson = null, progress = 70)
            val legacy = BackupFormat.encode(data.copy(cards = listOf(normal)), includeChecklist = false)
            val restored = BackupFormat.decode(legacy).cards.single()
            assertEquals(normal, restored)
            assertEquals(NoteType.NOTE, restored.noteType)
            val oldArchive = File(root, "legacy-v5.ontop")
            val manifest = JSONObject().put("format", "OnTop").put("version", 5).put("createdAt", 0)
                .put("files", JSONArray().put(JSONObject().put("path", "data.json").put("bytes", legacy.size)
                    .put("sha256", BackupFormat.digest(legacy))))
            ZipOutputStream(oldArchive.outputStream()).use { zip ->
                listOf("manifest.json" to manifest.toString().toByteArray(), "data.json" to legacy).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
            manager.prepare { oldArchive.inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            assertEquals(normal, db.cardDao().findById(1))
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun migrationElevenToTwelvePreservesOrdinaryNotesAndInteractionHistory() = runBlocking {
        val name = "checklist-migration-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/11.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { index ->
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) {
                        old.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table))
                    } }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                old.execSQL("INSERT INTO cards(id,title,description,size,createdAt,updatedAt,sortOrder,importance,progress,status) VALUES(1,'Старая','Текст','NORMAL',1,2,3,75,42,'ACTIVE')")
                old.execSQL("INSERT INTO note_interactions VALUES(1,1,'NOTE_OPENED',10)")
                old.version = 11
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).addMigrations(OnTopDatabase.MIGRATION_11_12).build()
            val note = db.cardDao().findById(1)!!
            assertEquals(NoteType.NOTE, note.noteType); assertEquals(42, note.progress)
            assertEquals("Текст", note.description); assertEquals(75, note.importance)
            assertNull(note.checklistJson); assertEquals(1L, db.noteInteractionDao().count(1, "NOTE_OPENED"))
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
