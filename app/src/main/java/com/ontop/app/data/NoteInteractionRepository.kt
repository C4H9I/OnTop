package com.ontop.app.data

import androidx.room.withTransaction
import com.ontop.app.reminders.ReminderSchedule
import com.ontop.app.reminders.schedule
import com.ontop.app.text.RichTextCodec

/** Local interaction data only; no network transport or background scheduling. */
class NoteInteractionRepository(private val db: OnTopDatabase) {
    private val dao get() = db.noteInteractionDao()
    suspend fun record(noteId: Long, type: NoteInteractionType, timestamp: Long = System.currentTimeMillis()) =
        dao.record(noteId, type, timestamp)
    suspend fun count(noteId: Long, type: NoteInteractionType) = dao.count(noteId, type.name)
    suspend fun lastOpenedAt(noteId: Long) = dao.lastOpenedAt(noteId)
    suspend fun history(noteId: Long, afterId: Long = 0, limit: Int = 500) = dao.history(noteId, afterId, limit)
    suspend fun aggregates() = dao.aggregates()

    internal data class RuleSnapshot(val schedule: ReminderSchedule, val enabled: Boolean, val pausedUntil: Long?)
    internal data class Snapshot(val card: CardEntity, val tags: Set<Long>, val reminder: RuleSnapshot?)
    internal suspend fun snapshot(id: Long): Snapshot? = db.withTransaction {
        db.cardDao().findById(id)?.let { card -> Snapshot(card.copy(updatedAt = 0,
            formatting = RichTextCodec.encode(RichTextCodec.decode(card.description, card.formatting))),
            db.tagDao().forCard(id).toSet(), db.reminderDao().forCard(id)?.let {
                RuleSnapshot(it.schedule(), it.enabled, it.pausedUntil)
            }) }
    }
    internal suspend fun recordEditIfChanged(before: Snapshot?, id: Long) {
        if (before != null && before != snapshot(id)) record(id, NoteInteractionType.NOTE_EDITED)
    }
}
