package com.ontop.app.widget

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.backup.BackupData
import com.ontop.app.backup.BackupFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetRotationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacySelectionsIndependentRotationConcurrentTapsAndDeletedNotes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val name = "rotation-test-${System.nanoTime()}"
        val store = WidgetSelectionStore(context, name)
        try {
            val a = db.cardDao().insert(CardEntity(title = "A", createdAt = 1, updatedAt = 1, sortOrder = 0, importance = 0))
            val b = db.cardDao().insert(CardEntity(title = "B", createdAt = 2, updatedAt = 2, sortOrder = 1, importance = 100))
            // This is the exact preference written by pre-rotation versions.
            context.getSharedPreferences(name, 0).edit().putLong("1", a).commit()
            assertEquals(WidgetMode.FIXED, store.selection(1).mode)
            store.save(2, WidgetSelection(WidgetMode.ROTATION, currentId = a, changedAt = 1000))
            assertEquals(setOf(2), store.rotationIds())
            coroutineScope {
                repeat(20) { launch(Dispatchers.Default) {
                    WidgetSelectionStore.mutex.withLock { WidgetRotation.select(store, db, 2, true, 2000) }
                } }
            }
            assertEquals(b, store.get(2)) // Concurrent taps in the same gap cause just one change.
            assertEquals(a, store.get(1))
            assertEquals(store.selection(2), WidgetSelectionStore(context, name).selection(2))
            assertFalse(WidgetRotation.select(store, db, 1, true, 5000))
            assertFalse(WidgetRotation.select(store, db, 2, false, 5000))
            assertFalse(WidgetRotation.select(store, db, 2, true, 3000, WidgetRotation.INTERVAL_MS))
            assertTrue(WidgetRotation.select(store, db, 2, true, 2000 + WidgetRotation.INTERVAL_MS, WidgetRotation.INTERVAL_MS))
            assertEquals(a, store.get(2))
            db.cardDao().delete(db.cardDao().findById(a)!!)
            assertTrue(WidgetRotation.select(store, db, 2, false, 9999999))
            assertEquals(b, store.get(2))
            val before = db.cardDao().findById(b)!!
            assertFalse(WidgetRotation.select(store, db, 2, true, 99999999))
            assertEquals(before, db.cardDao().findById(b))
            db.cardDao().delete(before)
            assertTrue(WidgetRotation.select(store, db, 2, false))
            assertNull(store.get(2))
            assertEquals("Пока нет заметок", CardWidget().load(context, 2, db, store).title)
            val c = db.cardDao().insert(before.copy(id = 0, title = "После пустого списка"))
            assertEquals(c, CardWidget().load(context, 2, db, store).cardId)
            store.remove(2)
            assertTrue(store.rotationIds().isEmpty())
            assertEquals(a, store.get(1))
        } finally { store.clear(); db.close() }
    }

    @Test fun importanceBackupRoundTripAndOlderArchives() {
        val cards = listOf(0, 50, 100).mapIndexed { i, value ->
            CardEntity(id = i + 1L, title = "Note", createdAt = 1, updatedAt = 2, sortOrder = i.toLong(), importance = value)
        }
        val data = BackupData(cards, emptyList(), emptyList())
        val encoded = BackupFormat.encode(data)
        assertEquals(data, BackupFormat.decode(encoded))
        val legacy = JSONObject(String(encoded, Charsets.UTF_8))
        val list = legacy.getJSONArray("cards")
        repeat(list.length()) { list.getJSONObject(it).remove("importance") }
        assertEquals(listOf(50, 50, 50), BackupFormat.decode(legacy.toString().toByteArray()).cards.map { it.importance })
        list.getJSONObject(0).put("importance", 101)
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.decode(legacy.toString().toByteArray()) }
    }

    @Test fun migrationSixToSevenPreservesNoteAndDefaultsImportance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = "importance-migration-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/6.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { i ->
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) repeat(indices.length()) { j ->
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                old.execSQL("INSERT INTO cards VALUES (7, 'Title', 'Body', 'WIDE', 100, 200, 3, NULL, NULL)")
                old.version = 6
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name)
                .addMigrations(OnTopDatabase.MIGRATION_6_7, OnTopDatabase.MIGRATION_7_8).build()
            val card = db.cardDao().findById(7)!!
            assertEquals(CardEntity(7, "Title", "Body", CardSize.WIDE, 100, 200, 3), card)
            db.cardDao().update(card.copy(importance = 100))
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals(100, db.cardDao().findById(7)!!.importance)
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
