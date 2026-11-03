package com.ontop.app

import com.ontop.app.data.CardEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardSearchTest {
    private fun card(id: Long, title: String, description: String) = CardEntity(
        id = id, title = title, description = description, createdAt = 0, updatedAt = 0, sortOrder = id)

    @Test fun matchesTitleAndContentAcrossAllCards() {
        val cards = listOf(card(1, "Важная мысль", "Проверять детали"),
            card(2, "Наблюдение", "Важная встреча"), card(3, "Идея", "Отложить"))
        assertEquals(listOf(1L, 2L), searchBoardCards(cards, "ВАЖНАЯ").map { it.id })
        assertEquals(listOf(1L), searchBoardCards(cards, "важная детали").map { it.id })
        assertEquals(cards, searchBoardCards(cards, "   "))
        assertEquals(emptyList<CardEntity>(), searchBoardCards(cards, "неизвестное"))
    }
}
