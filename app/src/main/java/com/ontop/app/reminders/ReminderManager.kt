package com.ontop.app.reminders

import android.content.Context
import com.ontop.app.data.CardEntity
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.data.ReminderEntity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal interface ReminderDelivery {
    fun schedule(id: Long, snooze: Boolean, at: Long)
    fun cancel(id: Long)
    fun cancelAlarms(id: Long) = cancel(id)
    fun show(rule: ReminderEntity, card: CardEntity)
}

/** Serializes receivers and foreground edits; stale delivered intents are checked against Room. */
internal class ReminderManager(
    private val db: OnTopDatabase,
    private val delivery: ReminderDelivery,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    constructor(context: Context, db: OnTopDatabase = OnTopDatabase.getInstance(context)) :
        this(db, AndroidReminderDelivery(context.applicationContext))

    companion object { private val mutex = Mutex() }
    private val dao = db.reminderDao()

    /** Backup replacement uses the same lock as receivers and foreground writes. */
    internal suspend fun <T> withDataLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    internal suspend fun finishReplacement(oldIds: List<Long>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            oldIds.forEach { delivery.cancel(it) }
            restoreLocked(rebase = true)
        }
    }

    suspend fun load(cardId: Long) = withContext(Dispatchers.IO) { dao.forCard(cardId) }

    suspend fun save(cardId: Long, hour: Int, minute: Int, enabled: Boolean) =
        save(cardId, ReminderSchedule(times = setOf(java.time.LocalTime.of(hour, minute).toSecondOfDay() / 60)), enabled)

    suspend fun save(cardId: Long, schedule: ReminderSchedule, enabled: Boolean, pausedUntil: Long? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(db.cardDao().findById(cardId) != null)
            val rule = storeRule(cardId, ReminderDraft(schedule, enabled, pausedUntil))
            delivery.cancel(rule.id)
            arm(rule)
        }
    }

    private suspend fun storeRule(cardId: Long, draft: ReminderDraft): ReminderEntity {
        val schedule = draft.schedule
        schedule.validate()
        val old = dao.forCard(cardId)
        val time = now()
        val first = schedule.times.minOrNull() ?: schedule.windowStart
        var rule = ReminderEntity(id = old?.id ?: 0, cardId = cardId, hour = first / 60, minute = first % 60,
            enabled = draft.enabled, createdAt = old?.createdAt ?: time, updatedAt = time,
            mode = schedule.mode.name, daysMask = schedule.daysMask, times = schedule.times.sorted().joinToString(","),
            intervalHours = schedule.intervalHours, windowStart = schedule.windowStart, windowEnd = schedule.windowEnd,
            quietStart = schedule.quietStart, quietEnd = schedule.quietEnd, pausedUntil = draft.pausedUntil)
        rule = rule.copy(nextAt = if (rule.enabled) next(time, rule) else null)
        if (old == null) rule = rule.copy(id = dao.insert(rule)) else check(dao.update(rule) == 1)
        return rule
    }

    /** Lock before the Room transaction, just like receivers: no inverted lock order. */
    suspend fun saveNote(card: CardEntity, isNew: Boolean, reminder: ReminderDraft?, reminderChanged: Boolean,
        tagIds: List<Long>? = null, newTagNames: List<String> = emptyList()): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = if (isNew) null else dao.forCard(card.id)
                var planned: ReminderEntity? = null
                try {
                    db.withTransaction {
                        val id = if (isNew) db.cardDao().insertAtEnd(card) else {
                            check(db.cardDao().update(card) == 1)
                            card.id
                        }
                        if (tagIds != null) db.tagDao().replaceForCard(id, tagIds, newTagNames)
                        if (reminderChanged && reminder != null) {
                            planned = storeRule(id, reminder)
                            delivery.cancel(planned!!.id)
                            arm(planned!!)
                        }
                        id
                    }
                } catch (error: Exception) {
                    // Rollback also removes a new card. Undo any partially scheduled OS alarm;
                    // keep the original failure so the editor can retry the entire document.
                    withContext(NonCancellable) {
                        runCatching {
                            planned?.let { delivery.cancel(it.id) }
                            if (planned != null && previous != null) arm(previous)
                        }
                    }
                    throw error
                }
            }
        }

    suspend fun deleteRule(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.get(id)?.let { check(dao.update(it.copy(enabled = false, nextAt = null, snoozeAt = null)) == 1) }
            delivery.cancel(id)
            dao.delete(id)
        }
    }

    suspend fun deleteCard(card: CardEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Cancel the OS alarms explicitly before the FK removes the rule.
            dao.forCard(card.id)?.let { rule ->
                check(dao.update(rule.copy(enabled = false, nextAt = null, snoozeAt = null)) == 1)
                delivery.cancel(rule.id)
            }
            check(db.cardDao().delete(card) == 1)
        }
    }

    suspend fun restore(rebase: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            restoreLocked(rebase)
        }
    }

    private suspend fun restoreLocked(rebase: Boolean) {
        for (old in dao.all()) {
            val time = now()
            val today = Instant.ofEpochMilli(time).atZone(zone()).toLocalDate()
            // Preserve a pending inexact alarm from today, only if still a valid slot.
            val pending = old.nextAt?.takeIf { at ->
                !rebase && Instant.ofEpochMilli(at).atZone(zone()).toLocalDate() >= today &&
                    next(at - 1, old) == at
            }
            val nextAt = pending ?: next(time, old)
            val rule = if (old.enabled) old.copy(nextAt = nextAt,
                snoozeAt = old.snoozeAt?.takeUnless { isPaused(old, time) })
                else old.copy(nextAt = null, snoozeAt = null)
            check(dao.update(rule) == 1)
            delivery.cancelAlarms(rule.id)
            // Do not dismiss a visible notification during routine reconciliation.
            if (rule.enabled) arm(rule) else delivery.cancel(rule.id)
        }
    }

    suspend fun fire(id: Long, snooze: Boolean, expectedAt: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rule = dao.get(id) ?: return@withLock
            if (!rule.enabled || expectedAt != (if (snooze) rule.snoozeAt else rule.nextAt)) return@withLock
            if (expectedAt > now()) { arm(rule); return@withLock }
            val time = now()
            val card = db.cardDao().findById(rule.cardId) ?: return@withLock
            val updated = if (snooze) rule.copy(snoozeAt = null)
                else rule.copy(nextAt = next(time, rule))
            check(dao.update(updated) == 1)
            // Keep the daily chain alive even when notifications have been denied.
            arm(updated)
            // Late inexact delivery entering quiet hours is skipped too, never queued.
            if (!isPaused(rule, time) && (snooze || rule.schedule().allowsScheduledDelivery(Instant.ofEpochMilli(time), zone()))) {
                delivery.show(updated, card)
            }
        }
    }

    suspend fun snooze(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rule = dao.get(id)?.takeIf { it.enabled } ?: return@withLock
            if (isPaused(rule, now())) return@withLock
            val updated = rule.copy(snoozeAt = now() + 10 * 60_000L)
            check(dao.update(updated) == 1)
            delivery.schedule(id, true, updated.snoozeAt!!)
        }
    }

    private fun isPaused(rule: ReminderEntity, time: Long) = (rule.pausedUntil ?: Long.MIN_VALUE) > time

    private fun next(time: Long, rule: ReminderEntity) = nextScheduleTime(
        Instant.ofEpochMilli(time), zone(), rule.schedule(), rule.pausedUntil?.let(Instant::ofEpochMilli)
    )?.toEpochMilli()

    private fun arm(rule: ReminderEntity) {
        if (!rule.enabled) return
        rule.nextAt?.let { delivery.schedule(rule.id, false, it) }
        rule.snoozeAt?.let { delivery.schedule(rule.id, true, it) }
    }
}
