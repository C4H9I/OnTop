package com.ontop.app

import com.ontop.app.data.CardEntity
import org.junit.Assert.*
import org.junit.Test

class BoardSortTest {
    private val cards = listOf(
        CardEntity(3, "C", createdAt = 10, updatedAt = 100, sortOrder = 1, importance = 50),
        CardEntity(1, "A", createdAt = 30, updatedAt = 30, sortOrder = 0, importance = 0),
        CardEntity(4, "D", createdAt = 40, updatedAt = 40, sortOrder = 2, importance = 100),
        CardEntity(2, "B", createdAt = 10, updatedAt = 100, sortOrder = 1, importance = 50))
    private fun ids(mode: BoardSort) = sortedBoardCards(cards, mode).map { it.id }

    @Test fun manualOrderIsStableAndUnaffectedByOtherModes() {
        val before = cards.map { it.copy() }
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(BoardSort.MANUAL))
        BoardSort.entries.forEach { ids(it) }
        assertEquals(before, cards)
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(BoardSort.MANUAL))
    }

    @Test fun importanceDescendingUsesManualThenIdForTies() {
        assertEquals(listOf(4L, 2L, 3L, 1L), ids(BoardSort.IMPORTANCE))
        assertEquals(listOf(1L, 2L, 3L, 4L), sortedBoardCards(cards.map { it.copy(importance = 50) }, BoardSort.IMPORTANCE).map { it.id })
    }

    @Test fun creationAndUpdateUseDifferentRealDatesAndStableTies() {
        assertEquals(listOf(4L, 1L, 2L, 3L), ids(BoardSort.CREATED))
        assertEquals(listOf(2L, 3L, 4L, 1L), ids(BoardSort.UPDATED))
    }

    @Test fun editsAdditionsAndDeletionReorderOnlyCurrentInput() {
        val edited = cards.map { if (it.id == 1L) it.copy(importance = 100) else it }
        assertEquals(listOf(1L, 4L, 2L, 3L), sortedBoardCards(edited, BoardSort.IMPORTANCE).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), sortedBoardCards(edited.filter { it.id != 4L }, BoardSort.IMPORTANCE).map { it.id })
        val added = cards + CardEntity(5, "E", createdAt = 50, updatedAt = 200, sortOrder = 3)
        assertEquals(5L, sortedBoardCards(added, BoardSort.UPDATED).first().id)
        assertTrue(sortedBoardCards(emptyList(), BoardSort.CREATED).isEmpty())
    }

    @Test fun boardSortDoesNotAffectSliderRandomSession() {
        val session = NoteSliderSession()
        session.enter(cards)
        session.order = NoteSliderOrder.RANDOM
        val before = session.ordered(cards).map { it.id }
        BoardSort.entries.forEach { mode ->
            assertEquals(before, session.ordered(sortedBoardCards(cards, mode)).map { it.id })
        }
        session.order = NoteSliderOrder.BOARD
        assertEquals(ids(BoardSort.MANUAL), session.ordered(cards).map { it.id })
    }

    @Test fun progressSortKeepsNullSeparateFromZeroAndStableTiesWithoutMutatingManualOrder() {
        val input = cards.map { it.copy(progress = when (it.id) { 1L -> null; 4L -> 0; else -> 100 }) }
        assertEquals(listOf(2L, 3L, 4L, 1L), sortedBoardCards(input, BoardSort.PROGRESS).map { it.id })
        val edited = input.map { if (it.id == 1L) it.copy(progress = 100) else it }
        assertEquals(listOf(1L, 2L, 3L, 4L), sortedBoardCards(edited, BoardSort.PROGRESS).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), sortedBoardCards(input, BoardSort.MANUAL).map { it.id })
        assertNull(input.single { it.id == 1L }.progress)
    }
}
