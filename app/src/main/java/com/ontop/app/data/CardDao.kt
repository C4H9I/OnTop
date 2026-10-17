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
    @Query("SELECT * FROM cards WHERE id = :id")
    abstract suspend fun findById(id: Long): CardEntity?

    @Query("SELECT * FROM cards ORDER BY sortOrder ASC, id ASC")
    abstract fun observeCards(): Flow<List<CardEntity>>

    @Insert
    abstract suspend fun insert(card: CardEntity): Long

    @Update
    abstract suspend fun update(card: CardEntity): Int

    @Delete
    abstract suspend fun delete(card: CardEntity): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM cards")
    protected abstract suspend fun nextSortOrder(): Long

    // Assign the position in the same transaction as the insert.
    @Transaction
    open suspend fun insertAtEnd(card: CardEntity): Long =
        insert(card.copy(sortOrder = nextSortOrder()))
}
