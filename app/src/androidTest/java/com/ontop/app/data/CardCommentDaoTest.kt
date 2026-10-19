package com.ontop.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardCommentDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun card(title: String) = CardEntity(title = title, description = "Текст",
        createdAt = 100, updatedAt = 200, sortOrder = 3)

    @Test
    fun orderingFilteringDeletionAndCascade() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        try {
            val cards = db.cardDao()
            val dao = db.cardCommentDao()
            val id = cards.insert(card("Мысль"))
            val other = cards.insert(card("Другая"))
            val original = cards.observeCards().first()
            val late = dao.insert(CardCommentEntity(cardId = id, text = "Позже", createdAt = 20))
            val first = dao.insert(CardCommentEntity(cardId = id, text = "Первый", createdAt = 10))
            val second = dao.insert(CardCommentEntity(cardId = id, text = "Второй", createdAt = 10))
            dao.insert(CardCommentEntity(cardId = other, text = "Отдельный", createdAt = 1))
            assertEquals(listOf(first, second, late), dao.observeComments(id).first().map { it.id })
            assertEquals(original, cards.observeCards().first())
            assertEquals(1, dao.deleteById(second))
            assertEquals(listOf(first, late), dao.observeComments(id).first().map { it.id })
            cards.delete(original.first { it.id == id })
            assertTrue(dao.observeComments(id).first().isEmpty())
            assertEquals(1, dao.observeComments(other).first().size)
        } finally { db.close() }
    }

    @Test
    fun sameFlowReceivesNewCommentsAndDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        try {
            val id = db.cardDao().insert(card("Мысль"))
            val dao = db.cardCommentDao()
            withTimeout(10_000) {
                val emissions = Channel<List<CardCommentEntity>>(Channel.UNLIMITED)
                val job = launch { dao.observeComments(id).collect { emissions.send(it) } }
                try {
                    assertTrue(emissions.receive().isEmpty())
                    val commentId = dao.insert(CardCommentEntity(cardId = id, text = "Мысль", createdAt = 1))
                    assertEquals(commentId, emissions.receiveAsFlow().first { it.isNotEmpty() }.single().id)
                    dao.deleteById(commentId)
                    assertTrue(emissions.receiveAsFlow().first { it.isEmpty() }.isEmpty())
                } finally { job.cancelAndJoin(); emissions.close() }
            }
        } finally { db.close() }
    }

    @Test
    fun migrationPreservesVersionOneCardAndEnablesComments() = runBlocking {
        // Isolated file with the exact v1 cards schema; never opens the user's ontop.db.
        val name = "migration-comments-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("CREATE TABLE IF NOT EXISTS `cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `size` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)")
                old.execSQL("INSERT INTO cards VALUES (7, 'Мысль', 'Текст', 'NORMAL', 100, 200, 3)")
                old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES (42, '8bcbe043ea2121692fedbb2e122290e4')")
                old.version = 1
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name)
                .addMigrations(OnTopDatabase.MIGRATION_1_2, OnTopDatabase.MIGRATION_2_3, OnTopDatabase.MIGRATION_3_4, OnTopDatabase.MIGRATION_4_5, OnTopDatabase.MIGRATION_5_6, OnTopDatabase.MIGRATION_6_7, OnTopDatabase.MIGRATION_7_8).build()
            val saved = db.cardDao().observeCards().first().single()
            assertEquals(card("Мысль").copy(id = 7), saved)
            val commentId = db.cardCommentDao().insert(CardCommentEntity(cardId = 7, text = "История", createdAt = 300))
            assertTrue(commentId > 0)
            db.cardDao().delete(saved)
            assertTrue(db.cardCommentDao().observeComments(7).first().isEmpty())
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
