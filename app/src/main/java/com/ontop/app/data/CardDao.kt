package com.ontop.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CardDao {
    @Query("DELETE FROM cards")
    abstract suspend fun deleteAll()
    @Query("SELECT * FROM cards")
    abstract suspend fun allCards(): List<CardEntity>
    @Query("SELECT * FROM cards WHERE status != 'TRASHED' AND id > :afterId ORDER BY id LIMIT :limit")
    abstract suspend fun analysisPage(afterId: Long, limit: Int): List<CardEntity>
    @Query("SELECT * FROM cards WHERE id = :id")
    abstract suspend fun findById(id: Long): CardEntity?

    @Query("SELECT * FROM cards WHERE status = 'ACTIVE' ORDER BY sortOrder ASC, id ASC")
    abstract fun observeCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards ORDER BY sortOrder ASC, id ASC")
    abstract fun observeAllCards(): Flow<List<CardEntity>>

    @Insert
    protected abstract suspend fun insertRaw(card: CardEntity): Long

    open suspend fun insert(card: CardEntity): Long = insertRaw(card.normalizedChecklist())

    @Update
    protected abstract suspend fun updateRaw(card: CardEntity): Int

    open suspend fun update(card: CardEntity): Int = updateRaw(card.normalizedChecklist())

    @Query("UPDATE cards SET importance = :importance, progress = CASE WHEN noteType = 'CHECKLIST' THEN progress ELSE :progress END, updatedAt = :updatedAt WHERE id = :id AND status != 'TRASHED'")
    abstract suspend fun updatePercentages(id: Long, importance: Int, progress: Int?, updatedAt: Long): Int

    @Delete
    abstract suspend fun delete(card: CardEntity): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM cards")
    protected abstract suspend fun nextSortOrder(): Long

    // Assign the position in the same transaction as the insert.
    @Transaction
    open suspend fun insertAtEnd(card: CardEntity): Long =
        insert(card.copy(sortOrder = nextSortOrder()))
}
