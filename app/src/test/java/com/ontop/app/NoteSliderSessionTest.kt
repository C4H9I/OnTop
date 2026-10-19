package com.ontop.app

import com.ontop.app.data.CardEntity
import org.junit.Assert.*
import org.junit.Test

class NoteSliderSessionTest {
    private val cards = (1L..100L).map { CardEntity(id = it, title = "$it", createdAt = it,
        updatedAt = 101 - it, sortOrder = it) }

    @Test fun selectionSurvivesReorderingAndDeletionUsesNearestPosition() {
        val session = NoteSliderSession()
        session.currentId = 51; session.index = 50
        session.order = NoteSliderOrder.CREATED
        assertEquals(51L, session.ordered(cards)[session.position(session.ordered(cards))].id)
        val remaining = cards.filter { it.id != 51L }
        assertEquals(50, session.position(remaining))
        assertEquals(0, session.position(emptyList()))
        assertEquals(0, session.position(cards.take(1)))
    }

    @Test fun randomSequenceIsStableAcrossEditsAndRemovals() {
        val session = NoteSliderSession()
        session.enter(cards); session.order = NoteSliderOrder.RANDOM
        val first = session.ordered(cards).map { it.id }
        assertEquals(cards.map { it.id }.toSet(), first.toSet())
        assertEquals(first, session.ordered(cards.map { it.copy(title = "edited") }).map { it.id })
        assertEquals(first.filter { it != 25L }, session.ordered(cards.filter { it.id != 25L }).map { it.id })
        session.order = NoteSliderOrder.CREATED
        assertEquals(100L, session.ordered(cards).first().id)
        session.order = NoteSliderOrder.UPDATED
        assertEquals(1L, session.ordered(cards).first().id)
    }
}
