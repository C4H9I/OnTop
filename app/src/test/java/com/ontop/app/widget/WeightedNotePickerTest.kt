package com.ontop.app.widget

import com.ontop.app.data.CardEntity
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class WeightedNotePickerTest {
    private fun card(id: Long, importance: Int = 50) = CardEntity(id = id, title = "$id",
        createdAt = 1, updatedAt = 1, sortOrder = id, importance = importance)

    @Test fun emptyAndSingleNote() {
        assertNull(chooseRotationNote(emptyList(), 1))
        val only = card(1)
        assertEquals(only, chooseRotationNote(listOf(only), null))
        assertEquals(only, chooseRotationNote(listOf(only), only.id))
    }

    @Test fun twoNotesAlwaysAlternateRegardlessOfImportance() {
        val cards = listOf(card(1, 0), card(2, 100))
        val random = Random(12)
        repeat(1000) {
            assertEquals(2L, chooseRotationNote(cards, 1, random)!!.id)
            assertEquals(1L, chooseRotationNote(cards, 2, random)!!.id)
        }
    }

    private fun distribution(cards: List<CardEntity>, current: Long? = null): Map<Long, Int> {
        val random = Random(121)
        return buildMap {
            repeat(120_000) {
                val id = chooseRotationNote(cards, current, random)!!.id
                put(id, getOrDefault(id, 0) + 1)
            }
        }
    }

    private fun assertDistribution(cards: List<CardEntity>, current: Long? = null) {
        val counts = distribution(cards, current)
        val eligible = cards.filter { it.id != current }
        val total = eligible.sumOf { 10 + it.importance }.toDouble()
        eligible.forEach {
            val expected = (10 + it.importance) / total
            assertEquals("id=${it.id}", expected, counts.getValue(it.id) / 120_000.0, 0.006)
        }
        if (current != null) assertFalse(counts.containsKey(current))
    }

    @Test fun tenDifferentWeightsFollowFormulaWithoutChangingNotes() {
        val cards = (0..9).map { card(it + 1L, it * 11) }
        val snapshot = cards.map { it.copy() }
        assertDistribution(cards)
        assertEquals(snapshot, cards)
    }

    @Test fun equalImportanceHasEqualProbability() {
        assertDistribution((1L..10L).map { card(it) })
    }

    @Test fun extremesRemainEligibleWithElevenToOneRatio() {
        assertDistribution(listOf(card(1, 0), card(2, 100)))
    }

    @Test fun currentNoteIsExcludedFromWeightedDistribution() {
        assertDistribution((1L..10L).map { card(it, (it * 10).toInt()) }, 10)
    }

    @Test fun missingCurrentNoteDoesNotExcludeExistingNotes() {
        val cards = (1L..10L).map { card(it) }
        assertDistribution(cards, 999)
        assertEquals(distribution(cards), distribution(cards, 999))
    }
}
