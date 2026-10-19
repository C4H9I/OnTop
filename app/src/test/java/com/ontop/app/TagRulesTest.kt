package com.ontop.app

import com.ontop.app.data.*
import org.junit.Assert.*
import org.junit.Test

class TagRulesTest {
    @Test fun russianCaseWhitespaceAndUnicodeFormsHaveOneKey() {
        assertEquals("Мои мысли", cleanTagName(" \tМои\u00a0  мысли\n"))
        assertEquals(tagNameKey("Мои мысли"), tagNameKey("  МОИ   МЫСЛИ "))
        assertEquals(tagNameKey("Йога"), tagNameKey("И\u0306ога"))
        assertEquals(tagNameKey("Été"), tagNameKey("E\u0301TE\u0301"))
    }
    @Test fun emptyAndExcessiveNamesAreRejected() {
        for (value in listOf("", " \t\n\u00a0", "a".repeat(101))) {
            assertThrows(IllegalArgumentException::class.java) { cleanTagName(value) }
        }
    }
    @Test fun filterThenSortPreservesInputAndUsesSeveralTopicsPerNote() {
        val cards = listOf(CardEntity(1, "A", createdAt = 1, updatedAt = 3, sortOrder = 0, importance = 25),
            CardEntity(2, "B", createdAt = 2, updatedAt = 2, sortOrder = 1, importance = 100),
            CardEntity(3, "C", createdAt = 3, updatedAt = 1, sortOrder = 2, importance = 50))
        val links = listOf(CardTagCrossRef(1, 10), CardTagCrossRef(2, 10), CardTagCrossRef(1, 20))
        val subset = filterCardsByTag(cards, links, 10)
        assertEquals(listOf(2L, 1L), sortedBoardCards(subset, BoardSort.IMPORTANCE).map { it.id })
        assertEquals(listOf(1L), filterCardsByTag(cards, links, 20).map { it.id })
        assertTrue(filterCardsByTag(cards, links, 30).isEmpty())
        assertEquals(cards, filterCardsByTag(cards, links, null))
        assertEquals(listOf(0L, 1L, 2L), cards.map { it.sortOrder })
    }
}
