package com.ontop.app.lifecycle

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.BoardViewModel
import com.ontop.app.data.*
import com.ontop.app.reminders.*
import com.ontop.app.text.*
import com.ontop.app.widget.rotationCandidates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.UUID

class NoteLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private class Delivery : ReminderDelivery {
        val alarms = mutableMapOf<Pair<Long, Boolean>, Long>()
        var shown = 0
        override fun schedule(id: Long, snooze: Boolean, at: Long) { alarms[id to snooze] = at }
        override fun cancel(id: Long) { alarms.keys.removeAll { it.first == id } }
        override fun show(rule: ReminderEntity, card: CardEntity) { shown++ }
    }

    @Test fun statusSuspendsDeliveryWithoutChangingSettingsAndRestoreRecalculatesSchedule() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val delivery = Delivery()
        var now = java.time.Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()
        val manager = ReminderManager(db, delivery, { now }, { ZoneId.of("UTC") })
        try {
            db.cardDao().insert(CardEntity(1, "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 4))
            manager.save(1, 9, 0, true)
            val original = db.reminderDao().forCard(1)!!
            manager.changeStatus(1, CardStatus.ARCHIVED)
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            val paused = db.reminderDao().forCard(1)!!
            assertEquals(original.copy(nextAt = null, snoozeAt = null), paused)
            assertTrue(delivery.alarms.isEmpty())
            assertTrue(rotationCandidates(db.cardDao().allCards(), emptyList(), emptySet(), false).isEmpty())
            now = original.nextAt!!
            manager.fire(original.id, false, now); manager.snooze(original.id); manager.restore(true)
            assertEquals(0, delivery.shown); assertTrue(delivery.alarms.isEmpty())
            manager.changeStatus(1, CardStatus.ACTIVE)
            val restored = db.reminderDao().forCard(1)!!
            assertTrue(restored.enabled); assertTrue(restored.nextAt!! > now)
            assertEquals(original.copy(nextAt = restored.nextAt), restored)
            assertEquals(restored.nextAt, delivery.alarms[restored.id to false])
            manager.changeStatus(1, CardStatus.TRASHED)
            manager.restore(true); manager.snooze(restored.id)
            assertTrue(delivery.alarms.isEmpty())
            manager.changeStatus(1, CardStatus.ACTIVE)
            assertEquals(1, db.cardDao().observeCards().first().size)
            manager.save(1, 9, 0, false)
            manager.changeStatus(1, CardStatus.ARCHIVED); manager.changeStatus(1, CardStatus.ACTIVE)
            assertFalse(db.reminderDao().forCard(1)!!.enabled)
            assertTrue(delivery.alarms.isEmpty())
        } finally { db.close() }
    }

    @Test fun permanentDeleteCascadesRelationsAndProtectsMediaReferencedByOtherStatuses() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val store = CardImageStore(app)
        val shared = "images/${UUID.randomUUID()}.jpg"
        val owned = "images/${UUID.randomUUID()}.jpg"
        var model: BoardViewModel? = null
        try {
            listOf(shared, owned).forEach { store.resolve(it).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) } }
            val rich = RichDocument(media = listOf(NoteMedia("own", 0, MediaKind.IMAGE, path = owned)))
            val card = CardEntity(1, "Удалить", createdAt = 1, updatedAt = 1, sortOrder = 0,
                imagePath = shared, formatting = RichTextCodec.encode(rich), status = CardStatus.TRASHED)
            db.cardDao().insert(card)
            db.cardDao().insert(CardEntity(2, "Архив", createdAt = 1, updatedAt = 1, sortOrder = 1,
                imagePath = shared, status = CardStatus.ARCHIVED))
            db.cardCommentDao().insert(CardCommentEntity(cardId = 1, text = "История", createdAt = 1))
            val tag = db.tagDao().create("Тема")
            db.tagDao().replaceForCard(1, listOf(tag), emptyList())
            ReminderManager(db, Delivery()).save(1, 9, 0, true)
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            val done = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model!!.permanentlyDelete(card) { done.complete(Unit) } }
            withTimeout(10_000) { done.await() }
            assertNull(db.cardDao().findById(1))
            assertTrue(db.cardCommentDao().all().isEmpty())
            assertNull(db.reminderDao().forCard(1))
            assertTrue(db.tagDao().allLinks().isEmpty())
            assertTrue(store.resolve(shared).exists()); assertFalse(store.resolve(owned).exists())
            assertNotNull(db.cardDao().findById(2))
        } finally {
            model?.viewModelScope?.cancel()
            db.close(); store.delete(shared); store.delete(owned)
        }
    }

    @Test fun migrationEightToNineKeepsExistingDataAndStatusSurvivesDatabaseReopen() = runBlocking {
        val name = "lifecycle-${UUID.randomUUID()}.db"
        var db: OnTopDatabase? = null
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/8.json").bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
            app.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { index ->
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                old.execSQL("INSERT INTO cards VALUES (1,'Старая','Текст','WIDE',10,20,3,'images/abc.jpg',NULL,75)")
                old.execSQL("INSERT INTO card_comments VALUES (2,1,'История',30)")
                old.version = 8
            }
            db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).addMigrations(OnTopDatabase.MIGRATION_8_9, OnTopDatabase.MIGRATION_9_10, OnTopDatabase.MIGRATION_10_11).build()
            val original = db.cardDao().findById(1)!!
            assertEquals(CardStatus.ACTIVE, original.status); assertEquals(75, original.importance)
            assertEquals("images/abc.jpg", original.imagePath)
            assertEquals("История", db.cardCommentDao().all().single().text)
            db.cardDao().update(original.copy(status = CardStatus.ARCHIVED))
            db.close()
            db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).build()
            assertEquals(original.copy(status = CardStatus.ARCHIVED), db.cardDao().findById(1))
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            db.cardDao().update(original.copy(status = CardStatus.TRASHED))
            db.close()
            db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).build()
            assertEquals(original.copy(status = CardStatus.TRASHED), db.cardDao().findById(1))
            ReminderManager(db, Delivery()).changeStatus(1, CardStatus.ACTIVE)
            assertEquals(1L, db.cardDao().observeCards().first().single().id)
        } finally { db?.close(); app.deleteDatabase(name) }
    }
}
