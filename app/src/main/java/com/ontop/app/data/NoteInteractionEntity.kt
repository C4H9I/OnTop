package com.ontop.app.data

import androidx.room.*

enum class NoteInteractionType {
    NOTE_OPENED, SLIDER_VIEWED, NOTE_EDITED, COMMENT_ADDED, WIDGET_OPENED;
    val isOpening get() = this in setOf(NOTE_OPENED, SLIDER_VIEWED, WIDGET_OPENED)
}

@Entity(tableName = "note_interactions", foreignKeys = [ForeignKey(entity = CardEntity::class,
    parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index(value = ["noteId", "timestamp"]), Index(value = ["eventType", "id"])])
data class NoteInteractionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long, val eventType: String, val timestamp: Long)

data class NoteInteractionAggregate(val noteId: Long, val eventType: String, val count: Long, val lastAt: Long)

@Dao
abstract class NoteInteractionDao {
    @Insert abstract suspend fun insert(event: NoteInteractionEntity): Long
    @Query("SELECT COUNT(*) FROM note_interactions WHERE noteId = :noteId AND eventType = :type")
    abstract suspend fun count(noteId: Long, type: String): Long
    @Query("SELECT MAX(timestamp) FROM note_interactions WHERE noteId = :noteId AND eventType IN ('NOTE_OPENED','SLIDER_VIEWED','WIDGET_OPENED')")
    abstract suspend fun lastOpenedAt(noteId: Long): Long?
    @Query("SELECT * FROM note_interactions WHERE noteId = :noteId AND id > :afterId ORDER BY id LIMIT :limit")
    abstract suspend fun history(noteId: Long, afterId: Long = 0, limit: Int = 500): List<NoteInteractionEntity>
    @Query("SELECT noteId, eventType, COUNT(*) AS count, MAX(timestamp) AS lastAt FROM note_interactions GROUP BY noteId, eventType ORDER BY noteId, eventType")
    abstract suspend fun aggregates(): List<NoteInteractionAggregate>
    @Query("SELECT * FROM note_interactions ORDER BY id")
    abstract suspend fun all(): List<NoteInteractionEntity>
    @Query("SELECT * FROM note_interactions WHERE eventType = :type ORDER BY id DESC LIMIT 1")
    protected abstract suspend fun latest(type: String): NoteInteractionEntity?
    @Query("SELECT noteId FROM note_interactions WHERE eventType IN ('NOTE_OPENED','SLIDER_VIEWED','WIDGET_OPENED') ORDER BY id DESC LIMIT 1")
    protected abstract suspend fun latestOpenedNote(): Long?
    @Query("SELECT EXISTS(SELECT 1 FROM cards WHERE id = :id)")
    protected abstract suspend fun available(id: Long): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM cards WHERE id = :id AND status != 'TRASHED')")
    protected abstract suspend fun canOpen(id: Long): Boolean

    /** Transaction serializes the latest-event check and insertion across all instances. */
    @Transaction
    open suspend fun record(noteId: Long, type: NoteInteractionType, timestamp: Long): Long? {
        if (!available(noteId)) return null
        if (type.isOpening) {
            if (!canOpen(noteId)) return null
            val previous = latest(type.name)
            if (previous?.noteId == noteId && latestOpenedNote() == noteId &&
                timestamp - previous.timestamp in 0 until 10_000) return null
        }
        return insert(NoteInteractionEntity(noteId = noteId, eventType = type.name, timestamp = timestamp))
    }
}
