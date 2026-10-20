package com.ontop.app

import com.ontop.app.data.CardEntity
import org.junit.Assert.*
import org.junit.Test

class NoteInteractionSessionTest {
    private val cards = (1L..3).map { CardEntity(it, "Note $it", createdAt = it, updatedAt = it, sortOrder = it) }

    @Test fun initialViewRestorationAndReturningFromEditorDoNotDuplicateEvents() {
        val session = NoteSliderSession()
        val viewed = mutableListOf<Long>()
        session.enter(cards)
        session.settled(1, false, viewed::add)
        repeat(5) { session.settled(1, false, viewed::add) }
        assertEquals(listOf(1L), viewed)
        session.enter(cards)
        session.settled(1, false, viewed::add)
        assertEquals(listOf(1L, 1L), viewed)
    }
    @Test fun onlyConfirmedTransitionsCountAndDataReplacementIsNotInteraction() {
        val session = NoteSliderSession()
        val viewed = mutableListOf<Long>()
        session.enter(cards)
        session.settled(1, false, viewed::add)
        session.settled(1, true, viewed::add) // Cancelled swipe.
        session.settled(3, true, viewed::add) // Intermediate animation pages never reach this API.
        session.settled(1, true, viewed::add)
        session.settled(2, false, viewed::add) // Current card removed by a data update.
        assertEquals(listOf(1L, 3L, 1L), viewed)
        val recreated = NoteSliderSession()
        recreated.settled(2, false, viewed::add)
        assertEquals(3, viewed.size)
    }
    @Test fun openingEmptySliderDoesNotCountBackgroundArrivals() {
        val session = NoteSliderSession()
        val viewed = mutableListOf<Long>()
        session.enter(emptyList())
        session.settled(1, false, viewed::add)
        assertTrue(viewed.isEmpty())
    }
}
