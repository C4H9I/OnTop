package com.ontop.app.widget

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WidgetTopicsStateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun independentFiltersRenameDeletionReassignmentAndPersistence() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val name = "widget-topics-${System.nanoTime()}"
        val store = WidgetSelectionStore(context, name)
        try {
            val cards = (1..3).map { db.cardDao().insert(CardEntity(title = "Card $it",
                createdAt = 1, updatedAt = 1, sortOrder = it.toLong())) }
            val first = db.tagDao().create("Размышления")
            val second = db.tagDao().create("Идеи")
            db.tagDao().replaceForCard(cards[0], listOf(first), emptyList())
            db.tagDao().replaceForCard(cards[1], listOf(first, second), emptyList())
            store.save(1, WidgetSelection(WidgetMode.ROTATION, tagIds = setOf(first)))
            store.save(2, WidgetSelection(WidgetMode.ROTATION, tagIds = setOf(second)))
            store.save(3, WidgetSelection(fixedId = cards[2]))
            store.save(4, WidgetSelection(WidgetMode.ROTATION, tagIds = setOf(first, second)))
            WidgetRotation.select(store, db, 1, false)
            WidgetRotation.select(store, db, 2, false)
            WidgetRotation.select(store, db, 4, false)
            assertTrue(store.get(1) in cards.take(2))
            assertEquals(cards[1], store.get(2))
            assertTrue(store.get(4) in cards.take(2))
            assertEquals(store.selection(1), WidgetSelectionStore(context, name).selection(1))
            val other = store.selection(2)
            val before = store.get(1)
            WidgetRotation.select(store, db, 1, true, now = Long.MAX_VALUE)
            assertNotEquals(before, store.get(1))
            assertEquals(other, store.selection(2))
            db.tagDao().rename(first, "Новое название")
            WidgetRotation.select(store, db, 1, false)
            assertEquals(setOf(first), store.selection(1).tagIds)
            // Repair a current note that loses its topic, even during the debounce interval.
            store.save(1, store.selection(1).copy(currentId = cards[0], changedAt = 1000))
            db.tagDao().clearCard(cards[0])
            WidgetRotation.select(store, db, 1, true, now = 1001)
            assertEquals(cards[1], store.get(1))
            db.tagDao().delete(first)
            WidgetRotation.select(store, db, 1, false)
            WidgetRotation.select(store, db, 4, false)
            assertNull(store.get(1))
            assertTrue(store.selection(1).tagFilterEnabled)
            assertTrue(store.selection(1).tagIds.isEmpty())
            assertEquals(setOf(second), store.selection(4).tagIds)
            assertEquals(cards[1], store.get(4))
            assertTrue(CardWidget().load(context, 1, db, store).title.contains("Измените настройки"))
            assertEquals(cards[2], store.get(3))
            db.cardDao().delete(db.cardDao().findById(cards[1])!!)
            WidgetRotation.select(store, db, 2, false)
            assertNull(store.get(2))
            // Removing one instance clears only its own filter; restore's clear resets all links.
            store.remove(1)
            assertFalse(store.selection(1).tagFilterEnabled)
            assertEquals(setOf(second), store.selection(2).tagIds)
            store.clear()
            assertFalse(WidgetSelectionStore(context, name).selection(2).tagFilterEnabled)
            assertTrue(store.rotationIds().isEmpty())
        } finally { store.clear(); db.close() }
    }
}
