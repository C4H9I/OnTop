package com.ontop.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TagDao {
    @Transaction @Query("SELECT * FROM tags ORDER BY normalizedName, id")
    abstract fun observeTags(): Flow<List<TagWithCards>>
    @Query("SELECT * FROM tags ORDER BY id") abstract suspend fun all(): List<TagEntity>
    @Query("SELECT * FROM card_tag_refs ORDER BY cardId, tagId") abstract suspend fun allLinks(): List<CardTagCrossRef>
    @Query("SELECT * FROM tags WHERE id = :id") abstract suspend fun find(id: Long): TagEntity?
    @Query("SELECT * FROM tags WHERE normalizedName = :key") abstract suspend fun findByKey(key: String): TagEntity?
    @Query("SELECT tagId FROM card_tag_refs WHERE cardId = :id ORDER BY tagId") abstract suspend fun forCard(id: Long): List<Long>
    @Insert abstract suspend fun insert(tag: TagEntity): Long
    @Insert abstract suspend fun insertLink(link: CardTagCrossRef)
    @Query("DELETE FROM card_tag_refs WHERE cardId = :cardId") abstract suspend fun clearCard(cardId: Long)
    @Query("DELETE FROM tags WHERE id = :id") abstract suspend fun delete(id: Long): Int
    @Query("DELETE FROM tags") abstract suspend fun deleteAll()
    @Query("UPDATE tags SET name = :name, normalizedName = :key WHERE id = :id")
    protected abstract suspend fun updateName(id: Long, name: String, key: String): Int

    @Transaction open suspend fun create(name: String): Long {
        val display = cleanTagName(name)
        val key = tagNameKey(display)
        require(findByKey(key) == null) { "Тема с таким названием уже существует" }
        return insert(TagEntity(name = display))
    }

    @Transaction open suspend fun rename(id: Long, name: String) {
        val display = cleanTagName(name)
        val key = tagNameKey(display)
        require(findByKey(key)?.id.let { it == null || it == id }) { "Тема с таким названием уже существует" }
        check(updateName(id, display, key) == 1) { "Тема удалена" }
    }

    /** Runs inside the same Room transaction as the document and reminder. No draft writes. */
    @Transaction open suspend fun replaceForCard(cardId: Long, ids: List<Long>, newNames: List<String>) {
        val selected = ids.filter { find(it) != null }.toMutableSet()
        newNames.forEach { raw ->
            val name = cleanTagName(raw)
            selected += findByKey(tagNameKey(name))?.id ?: insert(TagEntity(name = name))
        }
        clearCard(cardId)
        selected.forEach { insertLink(CardTagCrossRef(cardId, it)) }
    }
}
