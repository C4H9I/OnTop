package com.ontop.app.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class NoteInteractionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun database(block: suspend (OnTopDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        try {
            (1L..2).forEach { db.cardDao().insert(CardEntity(it, "Мысль $it", createdAt = 1, updatedAt = 1, sortOrder = it)) }
            block(db)
        } finally { db.close() }
    }

    @Test fun duplicateCheckIsAtomicTypesIndependentAndFastReturnsArePreserved() = runBlocking {
        database { db ->
            val repository = NoteInteractionRepository(db)
            coroutineScope { repeat(100) { launch(Dispatchers.IO) { repository.record(1, NoteInteractionType.NOTE_OPENED, 1000) } } }
            assertEquals(1L, repository.count(1, NoteInteractionType.NOTE_OPENED))
            repository.record(1, NoteInteractionType.WIDGET_OPENED, 1001)
            assertEquals(1L, repository.count(1, NoteInteractionType.WIDGET_OPENED))
            repository.record(2, NoteInteractionType.SLIDER_VIEWED, 1002)
            repository.record(1, NoteInteractionType.NOTE_OPENED, 1003)
            assertEquals(2L, repository.count(1, NoteInteractionType.NOTE_OPENED))
            repository.record(1, NoteInteractionType.NOTE_OPENED, 11003)
            assertEquals(3L, repository.count(1, NoteInteractionType.NOTE_OPENED))
            repeat(2) { repository.record(1, NoteInteractionType.NOTE_EDITED, 11003); repository.record(1, NoteInteractionType.COMMENT_ADDED, 11003) }
            assertEquals(2L, repository.count(1, NoteInteractionType.NOTE_EDITED))
            assertEquals(2L, repository.count(1, NoteInteractionType.COMMENT_ADDED))
        }
    }

    @Test fun canonicalFormattingAndReminderBookkeepingAreNotNoteEdits() = runBlocking {
        database { db ->
            val card = db.cardDao().findById(1)!!.copy(description = "ABC",
                formatting = "{\"v\":1,\"ranges\":[{\"s\":0,\"e\":3,\"k\":\"BOLD\"}]}")
            db.cardDao().update(card)
            val ruleId = db.reminderDao().insert(ReminderEntity(cardId = 1, hour = 9, minute = 0,
                enabled = true, createdAt = 1, updatedAt = 1))
            val repository = NoteInteractionRepository(db)
            val before = repository.snapshot(1)
            db.cardDao().update(card.copy(updatedAt = 100, formatting =
                "{\"ranges\":[{\"k\":\"BOLD\",\"e\":3,\"s\":0}],\"v\":1}"))
            val rule = db.reminderDao().forCard(1)!!
            db.reminderDao().update(rule.copy(updatedAt = 100, nextAt = 1000, snoozeAt = 1200))
            repository.recordEditIfChanged(before, 1)
            assertEquals(0L, repository.count(1, NoteInteractionType.NOTE_EDITED))
            db.cardDao().update(card.copy(formatting = null))
            repository.recordEditIfChanged(before, 1)
            assertEquals(1L, repository.count(1, NoteInteractionType.NOTE_EDITED))
            assertEquals(ruleId, db.reminderDao().forCard(1)!!.id)
        }
    }

    @Test fun sqlAggregatesPaginationArchiveAndCascadeUsePersistedHistory() = runBlocking {
        database { db ->
            val repository = NoteInteractionRepository(db)
            assertNull(repository.lastOpenedAt(1))
            assertTrue(repository.aggregates().isEmpty())
            repository.record(1, NoteInteractionType.NOTE_OPENED, 10)
            repository.record(1, NoteInteractionType.NOTE_EDITED, 20)
            repository.record(1, NoteInteractionType.NOTE_OPENED, 10010)
            assertEquals(10010L, repository.lastOpenedAt(1))
            val page = repository.history(1, limit = 1)
            assertEquals(1, page.size)
            assertEquals(2, repository.history(1, page.single().id).size)
            assertEquals(2L, repository.aggregates().single { it.eventType == "NOTE_OPENED" }.count)
            val card = db.cardDao().findById(1)!!
            db.cardDao().update(card.copy(status = CardStatus.ARCHIVED))
            assertEquals(3, repository.history(1).size)
            db.cardDao().delete(card)
            assertTrue(repository.history(1).isEmpty())
            assertNull(repository.record(1, NoteInteractionType.NOTE_OPENED, 20000))
        }
    }

    @Test fun migrationTenToElevenPreservesAllExistingRelationsAndMedia() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = "interactions-${java.util.UUID.randomUUID()}.db"
        var db: OnTopDatabase? = null
        val formatting = """{"v":2,"ranges":[],"media":[{"id":"image","at":0,"kind":"IMAGE","path":"images/abc.jpg"},{"id":"video","at":0,"kind":"YOUTUBE","url":"https://youtu.be/M7lc1UVf-VE"}]}"""
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/10.json").bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
            app.openOrCreateDatabase(name, 0, null).use { old ->
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
                old.execSQL("INSERT INTO cards(id,title,description,size,createdAt,updatedAt,sortOrder,imagePath,importance,progress,status) VALUES (1,'Мысль','Текст 🧠','WIDE',10,20,3,'images/abc.jpg',75,50,'ARCHIVED')")
                old.execSQL("UPDATE cards SET formatting = ? WHERE id = 1", arrayOf(formatting))
                old.execSQL("INSERT INTO card_comments VALUES (2,1,'История',30)")
                old.execSQL("INSERT INTO tags VALUES (3,'Тема','тема',40)")
                old.execSQL("INSERT INTO card_tag_refs VALUES (1,3)")
                old.execSQL("INSERT INTO reminders(id,cardId,hour,minute,enabled,createdAt,updatedAt) VALUES (4,1,9,0,1,10,20)")
                old.version = 10
            }
            db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).addMigrations(OnTopDatabase.MIGRATION_10_11).build()
            val card = db.cardDao().findById(1)!!
            assertEquals(50, card.progress); assertEquals(75, card.importance)
            assertEquals("images/abc.jpg", card.imagePath); assertEquals(CardStatus.ARCHIVED, card.status)
            assertEquals(formatting, card.formatting)
            assertEquals(1, db.cardCommentDao().all().size)
            assertEquals(listOf(3L), db.tagDao().forCard(1))
            assertNotNull(db.reminderDao().forCard(1))
            assertTrue(db.noteInteractionDao().all().isEmpty())
            db.noteInteractionDao().record(1, NoteInteractionType.NOTE_OPENED, 100)
            db.close()
            db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).build()
            assertEquals(1L, db.noteInteractionDao().count(1, "NOTE_OPENED"))
        } finally { db?.close(); app.deleteDatabase(name) }
    }
}
