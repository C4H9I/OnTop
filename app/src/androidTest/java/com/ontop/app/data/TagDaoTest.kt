package com.ontop.app.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TagDaoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun card(id: Long) = CardEntity(id, "Note $id", createdAt = 1, updatedAt = 2, sortOrder = id)

    @Test fun russianNamesDuplicatesRenameAndCascadesKeepOtherData() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        try {
            val dao = db.tagDao()
            db.cardDao().insert(card(1)); db.cardDao().insert(card(2))
            db.cardCommentDao().insert(CardCommentEntity(cardId = 1, text = "Комментарий", createdAt = 1))
            val work = dao.create("  Важные   мысли ")
            val home = dao.create("Личное")
            try { dao.create("ВАЖНЫЕ\u00a0МЫСЛИ"); fail("Duplicate accepted") } catch (_: IllegalArgumentException) { }
            try { dao.create("  "); fail("Blank accepted") } catch (_: IllegalArgumentException) { }
            dao.replaceForCard(1, listOf(work, home, work), listOf("ЛИЧНОЕ", "Новая тема"))
            dao.replaceForCard(2, listOf(work), emptyList())
            assertEquals(3, dao.forCard(1).size)
            assertEquals(3, dao.all().size)
            dao.rename(work, "Работа")
            assertEquals(work, dao.findByKey(tagNameKey("РАБОТА"))!!.id)
            try { dao.rename(work, "Личное"); fail("Duplicate rename accepted") } catch (_: IllegalArgumentException) { }
            assertEquals("Работа", dao.find(work)!!.name)
            dao.delete(work)
            assertTrue(dao.forCard(2).isEmpty())
            assertEquals(2, db.cardDao().allCards().size)
            assertEquals(1, db.cardCommentDao().all().size)
            dao.replaceForCard(1, listOf(home), emptyList())
            assertEquals(listOf(home), dao.forCard(1))
            db.cardDao().delete(card(1))
            assertTrue(dao.allLinks().isEmpty())
            assertEquals(2, dao.all().size) // Intentional shared topics survive a note's deletion.
            assertEquals(card(2), db.cardDao().findById(2))
        } finally { db.close() }
    }

    @Test fun noteTagsAndNewTopicsRollbackWithReminderAndSaveWithoutTagsWorks() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val delivery = object : ReminderDelivery {
            override fun schedule(id: Long, snooze: Boolean, at: Long) = Unit
            override fun cancel(id: Long) = Unit
            override fun show(rule: ReminderEntity, card: CardEntity) = Unit
        }
        try {
            db.cardDao().insert(card(1))
            val oldTag = db.tagDao().create("Прежняя тема")
            db.tagDao().replaceForCard(1, listOf(oldTag), emptyList())
            val old = db.tagDao().all()
            val manager = ReminderManager(db, delivery)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_tag_reminder BEFORE INSERT ON reminders BEGIN SELECT RAISE(ABORT, 'Test rollback'); END")
            try {
                manager.saveNote(card(1).copy(title = "Изменено"), false, ReminderDraft(), true,
                    listOf(oldTag), listOf("Несохранённая тема"))
                fail("Expected rollback")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(card(1), db.cardDao().findById(1))
            assertEquals(old, db.tagDao().all())
            assertEquals(listOf(oldTag), db.tagDao().forCard(1))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_tag_reminder")
            val id = manager.saveNote(card(0), true, null, false, listOf(oldTag), listOf("Новая", "НОВАЯ"))
            assertEquals(2, db.tagDao().forCard(id).size)
            assertEquals(2, db.tagDao().all().size)
            manager.saveNote(db.cardDao().findById(id)!!, false, null, false, emptyList())
            assertTrue(db.tagDao().forCard(id).isEmpty())
        } finally { db.close() }
    }

    @Test fun migrationSevenToEightPreservesIdsTextImportanceDatesAndComments() = runBlocking<Unit> {
        val name = "tag-migration-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/7.json").bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { i ->
                    val e = entities.getJSONObject(i); val table = e.getString("tableName")
                    old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = e.optJSONArray("indices")
                    if (indices != null) repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                old.execSQL("INSERT INTO cards(id,title,description,size,createdAt,updatedAt,sortOrder,importance) VALUES (7,'Прежняя','Текст','WIDE',10,20,3,75)")
                old.execSQL("INSERT INTO card_comments VALUES (9,7,'Комментарий',30)")
                old.version = 7
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).addMigrations(OnTopDatabase.MIGRATION_7_8).build()
            assertEquals(CardEntity(7, "Прежняя", "Текст", CardSize.WIDE, 10, 20, 3, importance = 75), db.cardDao().findById(7))
            assertEquals("Комментарий", db.cardCommentDao().all().single().text)
            assertTrue(db.tagDao().all().isEmpty())
            val tag = db.tagDao().create("Миграция")
            db.tagDao().replaceForCard(7, listOf(tag), emptyList())
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals(listOf(tag), db.tagDao().forCard(7))
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
