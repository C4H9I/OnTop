package com.ontop.app.widget

import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardTagCrossRef
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WidgetTopicsTest {
    private val cards = (1L..4L).map { CardEntity(id = it, title = "$it", createdAt = 1,
        updatedAt = 1, sortOrder = it, importance = if (it == 1L) 0 else 100) }
    private val links = listOf(CardTagCrossRef(1, 10), CardTagCrossRef(2, 10),
        CardTagCrossRef(2, 20), CardTagCrossRef(3, 20))

    @Test fun noFilterIncludesUntaggedNotes() {
        assertEquals(cards, rotationCandidates(cards, links, emptySet(), false))
    }
    @Test fun oneTopicAndOrUnionWithoutDuplicateTickets() {
        assertEquals(listOf(1L, 2L), rotationCandidates(cards, links, setOf(10), true).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), rotationCandidates(cards + cards[1], links, setOf(10, 20), true).map { it.id })
    }
    @Test fun exhaustedAndUnknownFiltersNeverExpandToAll() {
        assertTrue(rotationCandidates(cards, links, emptySet(), true).isEmpty())
        assertTrue(rotationCandidates(cards, links, setOf(999), true).isEmpty())
        assertTrue(rotationCandidates(emptyList(), links, setOf(10), true).isEmpty())
    }
    @Test fun filteredChoiceRetainsWeightsAndExcludesCurrent() {
        val eligible = rotationCandidates(cards, links, setOf(10), true)
        val random = Random(133)
        repeat(100) { assertEquals(2L, chooseRotationNote(eligible, 1, random)!!.id) }
        val counts = mutableMapOf<Long, Int>()
        repeat(120_000) { val id = chooseRotationNote(eligible, null, random)!!.id
            counts[id] = counts.getOrDefault(id, 0) + 1 }
        assertEquals(11.0, counts.getValue(2).toDouble() / counts.getValue(1), 0.5)
        assertEquals(cards[0], chooseRotationNote(rotationCandidates(cards, listOf(links[0]), setOf(10), true), 1))
    }
}
