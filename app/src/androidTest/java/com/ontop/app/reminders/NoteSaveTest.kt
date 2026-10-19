package com.ontop.app.reminders

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class NoteSaveTest {
    private class Delivery : ReminderDelivery {
        val alarms = mutableMapOf<Pair<Long, Boolean>, Long>()
        var failOnce = false
        override fun schedule(id: Long, snooze: Boolean, at: Long) {
            if (failOnce) { failOnce = false; throw IOException("AlarmManager failure") }
            alarms[id to snooze] = at
        }
        override fun cancel(id: Long) { alarms.keys.removeAll { it.first == id } }
        override fun show(rule: ReminderEntity, card: CardEntity) = Unit
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-10-19T08:00:00Z").toEpochMilli()
    private fun note(title: String = "Заметка") = CardEntity(title = title, description = "Текст", createdAt = 1, updatedAt = 1, sortOrder = 0)

    @Test fun newNoteCanSaveWithoutRuleOrWithPreparedRuleAndKeepsOldData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val delivery = Delivery()
        val manager = ReminderManager(db, delivery, { now }, { ZoneId.of("UTC") })
        try {
            val old = db.cardDao().insert(note("Старая").copy(imagePath = "images/old.jpg"))
            db.cardCommentDao().insert(CardCommentEntity(cardId = old, text = "История", createdAt = 1))
            val draft = ReminderDraft(ReminderSchedule(times = setOf(540, 1080), daysMask = 21), true)
            assertTrue(db.reminderDao().all().isEmpty())
            val plain = manager.saveNote(note(), true, null, false)
            assertNull(db.reminderDao().forCard(plain))
            val prepared = manager.saveNote(note("С расписанием"), true, draft, true)
            val rule = db.reminderDao().forCard(prepared)!!
            assertEquals(draft, rule.draft())
            assertEquals(prepared, rule.cardId)
            assertEquals(rule.nextAt, delivery.alarms[rule.id to false])
            assertEquals(3, db.cardDao().observeCards().first().size)
            assertEquals("images/old.jpg", db.cardDao().findById(old)!!.imagePath)
            assertEquals("История", db.cardCommentDao().observeComments(old).first().single().text)
        } finally { db.close() }
    }

    @Test fun failedScheduleRollsBackNewNoteAndRetryCreatesOneDocument() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val delivery = Delivery()
        val manager = ReminderManager(db, delivery, { now }, { ZoneId.of("UTC") })
        val draft = ReminderDraft(enabled = true)
        try {
            delivery.failOnce = true
            assertTrue(runCatching { manager.saveNote(note(), true, draft, true) }.isFailure)
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            assertTrue(db.reminderDao().all().isEmpty())
            assertTrue(delivery.alarms.isEmpty())
            val id = manager.saveNote(note(), true, draft, true)
            assertEquals(1, db.cardDao().observeCards().first().size)
            assertEquals(id, db.reminderDao().all().single().cardId)
            assertEquals(1, delivery.alarms.size)
        } finally { db.close() }
    }

    @Test fun failedEditPreservesWholeDocumentAndRuleAndUnchangedReminderKeepsSnooze() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val delivery = Delivery()
        val manager = ReminderManager(db, delivery, { now }, { ZoneId.of("UTC") })
        try {
            val id = manager.saveNote(note(), true, ReminderDraft(enabled = true), true)
            val original = db.cardDao().findById(id)!!
            manager.snooze(db.reminderDao().forCard(id)!!.id)
            val rule = db.reminderDao().forCard(id)!!
            val alarms = delivery.alarms.toMap()
            val edited = original.copy(title = "Изменено", description = "Новый текст", size = CardSize.WIDE, imagePath = "images/new.jpg")
            val draft = ReminderDraft(ReminderSchedule(times = setOf(1080)), true)
            delivery.failOnce = true
            assertTrue(runCatching { manager.saveNote(edited, false, draft, true) }.isFailure)
            assertEquals(original, db.cardDao().findById(id))
            assertEquals(rule, db.reminderDao().forCard(id))
            assertEquals(alarms, delivery.alarms)
            manager.saveNote(edited, false, rule.draft(), false)
            assertEquals(edited, db.cardDao().findById(id))
            assertEquals(rule, db.reminderDao().forCard(id))
            assertEquals(alarms, delivery.alarms)
            manager.saveNote(edited, false, draft, true)
            assertEquals(rule.id, db.reminderDao().forCard(id)!!.id)
            assertEquals(draft, db.reminderDao().forCard(id)!!.draft())
            assertNull(db.reminderDao().forCard(id)!!.snoozeAt)
        } finally { db.close() }
    }
}
