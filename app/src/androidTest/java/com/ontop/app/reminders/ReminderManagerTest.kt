package com.ontop.app.reminders

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Delivery : ReminderDelivery {
        val alarms = mutableMapOf<Pair<Long, Boolean>, Long>()
        val cancelled = mutableListOf<Long>()
        val titles = mutableListOf<String>()
        override fun schedule(id: Long, snooze: Boolean, at: Long) { alarms[id to snooze] = at }
        override fun cancel(id: Long) { cancelled += id; alarms.keys.removeAll { it.first == id } }
        override fun show(rule: ReminderEntity, card: CardEntity) { titles += card.title }
    }

    @Test fun dailySnoozeEditingDisablingDeletionAndStaleAlarms() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        var time = Instant.parse("2026-10-18T05:00:00Z").toEpochMilli()
        val delivery = Delivery()
        val manager = ReminderManager(db, delivery, { time }, { ZoneId.of("Europe/Moscow") })
        try {
            val card = CardEntity(title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0)
            val cardId = db.cardDao().insert(card)
            manager.save(cardId, 9, 0, true)
            val original = manager.load(cardId)!!
            val daily = original.nextAt!!
            time = daily
            db.cardDao().update(card.copy(id = cardId, title = "Изменённая мысль"))
            manager.fire(original.id, false, daily)
            assertEquals(listOf("Изменённая мысль"), delivery.titles)
            val tomorrow = manager.load(cardId)!!.nextAt!!
            assertTrue(tomorrow > time)
            manager.fire(original.id, false, daily)
            assertEquals(1, delivery.titles.size)
            manager.snooze(original.id)
            val snoozed = manager.load(cardId)!!
            assertEquals(time + 600_000, snoozed.snoozeAt)
            assertEquals(tomorrow, snoozed.nextAt)
            time = snoozed.snoozeAt!!
            manager.fire(original.id, true, time)
            assertNull(manager.load(cardId)!!.snoozeAt)
            assertEquals(tomorrow, manager.load(cardId)!!.nextAt)
            assertEquals(2, delivery.titles.size)
            manager.snooze(original.id)
            manager.save(cardId, 10, 30, false)
            assertTrue(delivery.alarms.isEmpty())
            manager.fire(original.id, false, tomorrow)
            assertEquals(2, delivery.titles.size)
            manager.save(cardId, 10, 30, true)
            assertEquals(original.id, manager.load(cardId)!!.id)
            manager.deleteCard(db.cardDao().findById(cardId)!!)
            assertNull(db.reminderDao().get(original.id))
            assertTrue(delivery.alarms.isEmpty())
            assertTrue(delivery.cancelled.contains(original.id))
        } finally { db.close() }
    }

    @Test fun restorationRebasesZoneAndKeepsIndependentSnoozes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        var time = Instant.parse("2026-10-18T05:00:00Z").toEpochMilli()
        var zone = ZoneId.of("Europe/Moscow")
        val first = Delivery()
        val manager = ReminderManager(db, first, { time }, { zone })
        try {
            val ids = (1..2).map { db.cardDao().insert(CardEntity(title = "Карточка $it", createdAt = 1, updatedAt = 1, sortOrder = it.toLong())) }
            ids.forEach { manager.save(it, 9, 0, true) }
            val rule = manager.load(ids[0])!!
            manager.snooze(rule.id)
            val snoozeAt = manager.load(ids[0])!!.snoozeAt
            val restored = Delivery()
            val reopened = ReminderManager(db, restored, { time }, { zone })
            zone = ZoneId.of("UTC")
            reopened.restore(rebase = true)
            assertEquals(3, restored.alarms.size)
            assertEquals(Instant.parse("2026-10-18T09:00:00Z").toEpochMilli(), restored.alarms[rule.id to false])
            assertEquals(snoozeAt, restored.alarms[rule.id to true])
            time = Instant.parse("2026-10-18T09:05:00Z").toEpochMilli()
            reopened.restore() // App start preserves today's delayed inexact alarm.
            assertEquals(Instant.parse("2026-10-18T09:00:00Z").toEpochMilli(), restored.alarms[rule.id to false])
            reopened.restore(rebase = true)
            assertEquals(Instant.parse("2026-10-19T09:00:00Z").toEpochMilli(), restored.alarms[rule.id to false])
            reopened.deleteRule(rule.id)
            assertNull(db.reminderDao().get(rule.id))
            assertEquals(1, restored.alarms.size)
        } finally { db.close() }
    }
}
