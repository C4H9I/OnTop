package com.ontop.app.reminders

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FlexibleReminderMigrationTest {
    @Test fun fourToFivePreservesDailyRulesCardsCommentsImagesAndPendingAlarms() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "flexible-migration-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        val nextAt = Instant.parse("2026-10-19T09:30:00Z").toEpochMilli()
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/4.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO cards VALUES (7, 'Мысль', 'Описание', 'WIDE', 100, 200, 3, 'images/abc.jpg')")
                old.execSQL("INSERT INTO card_comments VALUES (9, 7, 'Размышление', 300)")
                old.execSQL("INSERT INTO reminders VALUES (11, 7, 9, 30, 1, 400, 500, $nextAt, 600)")
                old.execSQL("INSERT INTO cards VALUES (8, 'Другая', '', 'NORMAL', 100, 200, 4, NULL)")
                old.execSQL("INSERT INTO reminders VALUES (12, 8, 17, 45, 0, 400, 500, NULL, NULL)")
                old.version = 4
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name)
                .addMigrations(OnTopDatabase.MIGRATION_4_5, OnTopDatabase.MIGRATION_5_6, OnTopDatabase.MIGRATION_6_7, OnTopDatabase.MIGRATION_7_8, OnTopDatabase.MIGRATION_8_9, OnTopDatabase.MIGRATION_9_10, OnTopDatabase.MIGRATION_10_11).build()
            val card = db.cardDao().findById(7)!!
            assertEquals(CardEntity(7, "Мысль", "Описание", CardSize.WIDE, 100, 200, 3, "images/abc.jpg"), card)
            assertEquals("Размышление", db.cardCommentDao().observeComments(7).first().single().text)
            val oldRule = db.reminderDao().forCard(7)!!
            assertEquals(11L, oldRule.id)
            assertEquals(400L, oldRule.createdAt)
            assertEquals(500L, oldRule.updatedAt)
            assertEquals(nextAt, oldRule.nextAt)
            assertEquals(600L, oldRule.snoozeAt)
            assertEquals(ReminderSchedule(times = setOf(570)), oldRule.schedule())
            assertNull(oldRule.pausedUntil)
            val disabled = db.reminderDao().forCard(8)!!
            assertFalse(disabled.enabled)
            assertEquals(setOf(1065), disabled.schedule().times)
            val calls = mutableMapOf<Pair<Long, Boolean>, Long>()
            val manager = ReminderManager(db, object : ReminderDelivery {
                override fun schedule(id: Long, snooze: Boolean, at: Long) { calls[id to snooze] = at }
                override fun cancel(id: Long) { calls.keys.removeAll { it.first == id } }
                override fun show(rule: ReminderEntity, card: CardEntity) = Unit
            }, { nextAt - 1000 }, { ZoneId.of("UTC") })
            manager.restore()
            assertEquals(nextAt, calls[11L to false])
            assertFalse(calls.keys.any { it.first == 12L })
            val changed = oldRule.copy(mode = "INTERVAL", daysMask = 5, times = "540,1080", intervalHours = 6,
                windowStart = 1260, windowEnd = 420, quietStart = 1380, quietEnd = 360, pausedUntil = nextAt + 86400000)
            db.reminderDao().update(changed)
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals(changed, db.reminderDao().forCard(7))
            db.cardDao().delete(card)
            assertNull(db.reminderDao().forCard(7))
            assertTrue(db.cardCommentDao().observeComments(7).first().isEmpty())
            assertNotNull(db.reminderDao().forCard(8))
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
