package com.ontop.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CardCommentDao {
    @Query("SELECT * FROM card_comments ORDER BY id")
    suspend fun all(): List<CardCommentEntity>
    @Query("SELECT * FROM card_comments WHERE cardId = :cardId AND (createdAt > :afterTime OR (createdAt = :afterTime AND id > :afterId)) ORDER BY createdAt, id LIMIT :limit")
    suspend fun analysisPage(cardId: Long, afterTime: Long, afterId: Long, limit: Int): List<CardCommentEntity>
    @Query("SELECT * FROM card_comments WHERE cardId = :cardId ORDER BY createdAt ASC, id ASC")
    fun observeComments(cardId: Long): Flow<List<CardCommentEntity>>

    @Insert
    suspend fun insert(comment: CardCommentEntity): Long

    @Query("DELETE FROM card_comments WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
