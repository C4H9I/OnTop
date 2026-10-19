package com.ontop.app.reminders

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FlexibleReminderManagerTest {
    private class Delivery : ReminderDelivery {
        val alarms = mutableMapOf<Pair<Long, Boolean>, Long>()
        var shown = 0
        override fun schedule(id: Long, snooze: Boolean, at: Long) { alarms[id to snooze] = at }
        override fun cancel(id: Long) { alarms.keys.removeAll { it.first == id } }
        override fun show(rule: ReminderEntity, card: CardEntity) { shown++ }
    }

    @Test fun intervalLateDeliveryQuietHoursSnoozePauseResumeEditingAndDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, OnTopDatabase::class.java).build()
        fun millis(value: String) = Instant.parse(value).toEpochMilli()
        var time = millis("2026-10-19T08:00:00Z")
        val delivery = Delivery()
        val manager = ReminderManager(db, delivery, { time }, { ZoneId.of("UTC") })
        try {
            val cardId = db.cardDao().insert(CardEntity(title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0))
            val schedule = ReminderSchedule(mode = ScheduleMode.INTERVAL, intervalHours = 3, windowStart = 540,
                windowEnd = 1260, quietStart = 600, quietEnd = 780)
            manager.save(cardId, schedule, true)
            val rule = manager.load(cardId)!!
            val nine = rule.nextAt!!
            time = millis("2026-10-19T10:05:00Z")
            manager.fire(rule.id, false, nine) // Delivery has entered quiet hours: skip, don't queue.
            assertEquals(0, delivery.shown)
            assertEquals(millis("2026-10-19T15:00:00Z"), manager.load(cardId)!!.nextAt)
            manager.fire(rule.id, false, nine)
            assertEquals(0, delivery.shown)
            manager.snooze(rule.id) // Explicit user action may pass through quiet hours.
            time += 600_000
            val base = manager.load(cardId)!!.nextAt
            manager.fire(rule.id, true, time)
            assertEquals(1, delivery.shown)
            assertEquals(base, manager.load(cardId)!!.nextAt)
            manager.snooze(rule.id)
            val pause = millis("2026-10-20T16:00:00Z")
            manager.save(cardId, schedule, true, pause)
            assertNull(manager.load(cardId)!!.snoozeAt)
            assertEquals(millis("2026-10-20T18:00:00Z"), manager.load(cardId)!!.nextAt)
            assertEquals(1, delivery.alarms.size)
            manager.snooze(rule.id)
            assertNull(manager.load(cardId)!!.snoozeAt)
            val restored = ReminderManager(db, delivery, { time }, { ZoneId.of("UTC") })
            restored.restore(true)
            assertEquals(pause, restored.load(cardId)!!.pausedUntil)
            assertEquals(1, delivery.alarms.size)
            manager.save(cardId, schedule, true, null)
            assertEquals(base, manager.load(cardId)!!.nextAt)
            time = millis("2026-10-19T15:37:00Z")
            manager.fire(rule.id, false, base!!)
            assertEquals(millis("2026-10-19T18:00:00Z"), manager.load(cardId)!!.nextAt)
            assertEquals(2, delivery.shown)
            manager.save(cardId, ReminderSchedule(times = setOf(1200)), true)
            manager.fire(rule.id, false, millis("2026-10-19T18:00:00Z"))
            assertEquals(2, delivery.shown)
            assertEquals(1, delivery.alarms.size)
            manager.save(cardId, schedule, false)
            assertTrue(delivery.alarms.isEmpty())
            manager.restore(true)
            assertTrue(delivery.alarms.isEmpty())
            manager.save(cardId, schedule, true)
            manager.deleteCard(db.cardDao().findById(cardId)!!)
            assertTrue(delivery.alarms.isEmpty())
            assertTrue(db.reminderDao().all().isEmpty())
        } finally { db.close() }
    }

    @Test fun restorationChangesZoneAndSystemTimeWithoutDuplicateOrQueuedOccurrences() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, OnTopDatabase::class.java).build()
        var time = Instant.parse("2026-10-19T08:00:00Z").toEpochMilli()
        var zone = ZoneId.of("UTC")
        val delivery = Delivery()
        val manager = ReminderManager(db, delivery, { time }, { zone })
        try {
            val card = db.cardDao().insert(CardEntity(title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0))
            manager.save(card, ReminderSchedule(times = setOf(540, 1080), daysMask = 1), true)
            val rule = manager.load(card)!!
            zone = ZoneId.of("Europe/Moscow")
            manager.restore(true)
            val expected = Instant.parse("2026-10-19T15:00:00Z").toEpochMilli()
            assertEquals(expected, manager.load(card)!!.nextAt)
            repeat(3) { manager.restore() }
            assertEquals(1, delivery.alarms.size)
            time = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
            manager.restore(true)
            assertEquals(Instant.parse("2026-10-26T06:00:00Z").toEpochMilli(), manager.load(card)!!.nextAt)
            manager.fire(rule.id, false, expected)
            assertEquals(0, delivery.shown)
            time = Instant.parse("2026-10-19T05:00:00Z").toEpochMilli()
            manager.restore(true)
            assertEquals(Instant.parse("2026-10-19T06:00:00Z").toEpochMilli(), manager.load(card)!!.nextAt)
            assertEquals(1, delivery.alarms.size)
        } finally { db.close() }
    }
}
