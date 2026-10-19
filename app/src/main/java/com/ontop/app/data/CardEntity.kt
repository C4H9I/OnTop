package com.ontop.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CardSize { NORMAL, WIDE }

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val size: CardSize = CardSize.NORMAL,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Long,
    val imagePath: String? = null,
    val formatting: String? = null
)
