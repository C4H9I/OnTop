package com.ontop.app.data

import androidx.room.*
import java.text.Normalizer
import java.util.Locale

fun cleanTagName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("[\\s\\p{Z}]+"), " ").trim().also {
        require(it.isNotEmpty()) { "Введите название темы" }
        require(it.length <= 100) { "Название темы должно быть не длиннее 100 символов" }
    }

fun tagNameKey(value: String): String = cleanTagName(value).lowercase(Locale.ROOT)

@Entity(tableName = "tags", indices = [Index(value = ["normalizedName"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String = tagNameKey(name),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "card_tag_refs", primaryKeys = ["cardId", "tagId"],
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("tagId")])
data class CardTagCrossRef(val cardId: Long, val tagId: Long)

data class TagWithCards(@Embedded val tag: TagEntity,
    @Relation(parentColumn = "id", entityColumn = "tagId") val links: List<CardTagCrossRef>)
