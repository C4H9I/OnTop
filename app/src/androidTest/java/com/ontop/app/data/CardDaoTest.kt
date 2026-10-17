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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: OnTopDatabase
    private lateinit var dao: CardDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        dao = database.cardDao()
    }

    @After
    fun tearDown() { database.close() }

    private fun card(title: String, sortOrder: Long = 0) = CardEntity(
        title = title, description = "Подробный текст", size = CardSize.WIDE,
        createdAt = 100, updatedAt = 100, sortOrder = sortOrder
    )

    @Test
    fun insertReadUpdateDelete() = runBlocking {
        assertTrue(dao.observeCards().first().isEmpty())
        val original = card("Мысль")
        val id = dao.insert(original)
        assertTrue(id > 0)
        val saved = dao.observeCards().first().single()
        assertEquals(original.copy(id = id), saved)

        val edited = saved.copy(title = "Новая мысль", description = "Изменённый текст",
            size = CardSize.NORMAL, updatedAt = 200)
        assertEquals(1, dao.update(edited))
        assertEquals(edited, dao.observeCards().first().single())
        assertEquals(100L, edited.createdAt)
        assertEquals(1, dao.delete(edited))
        assertTrue(dao.observeCards().first().isEmpty())
    }

    @Test
    fun sortOrderAndStableTieBreak() = runBlocking {
        val last = dao.insert(card("Последняя", sortOrder = 20))
        val first = dao.insert(card("Первая", sortOrder = 5))
        val second = dao.insert(card("Вторая", sortOrder = 5))
        val appended = dao.insertAtEnd(card("Добавленная"))
        val cards = dao.observeCards().first()
        assertEquals(listOf(first, second, last, appended), cards.map { it.id })
        assertEquals(21L, cards.last().sortOrder)
    }

    @Test
    fun flowEmitsInsertUpdateAndDelete() = runBlocking {
        withTimeout(10_000) {
            val emissions = Channel<List<CardEntity>>(Channel.UNLIMITED)
            val observer = launch { dao.observeCards().collect { emissions.send(it) } }
            try {
                assertTrue(emissions.receive().isEmpty())
                val id = dao.insertAtEnd(card("Мысль"))
                val saved = emissions.receiveAsFlow().first { it.isNotEmpty() }.single()
                assertEquals(id, saved.id)
                dao.update(saved.copy(title = "Изменено"))
                val updated = emissions.receiveAsFlow().first { it.singleOrNull()?.title == "Изменено" }
                assertEquals("Изменено", updated.single().title)
                dao.delete(saved)
                assertTrue(emissions.receiveAsFlow().first { it.isEmpty() }.isEmpty())
            } finally {
                observer.cancelAndJoin()
                emissions.close()
            }
        }
    }

    @Test
    fun fileDatabaseKeepsDataAfterReopen() = runBlocking {
        // An isolated test database; never open or delete the user's ontop.db.
        val name = "card-dao-test-${System.nanoTime()}.db"
        var diskDatabase: OnTopDatabase? = null
        try {
            diskDatabase = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            val id = diskDatabase.cardDao().insertAtEnd(card("Сохранённая мысль"))
            val expected = diskDatabase.cardDao().observeCards().first().single()
            diskDatabase.close()
            diskDatabase = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals(id, diskDatabase.cardDao().observeCards().first().single().id)
            assertEquals(expected, diskDatabase.cardDao().observeCards().first().single())
        } finally {
            diskDatabase?.close()
            context.deleteDatabase(name)
        }
    }
}
