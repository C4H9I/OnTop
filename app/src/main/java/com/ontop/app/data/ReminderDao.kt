package com.ontop.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE cardId = :cardId")
    suspend fun forCard(cardId: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE cardId = :cardId")
    fun observeForCard(cardId: Long): Flow<ReminderEntity?>
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>
    @Insert suspend fun insert(rule: ReminderEntity): Long
    @Update suspend fun update(rule: ReminderEntity): Int
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long): Int
}
