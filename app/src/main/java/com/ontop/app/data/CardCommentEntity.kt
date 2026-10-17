package com.ontop.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "card_comments",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"],
        childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")]
)
data class CardCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val text: String,
    val createdAt: Long
)
