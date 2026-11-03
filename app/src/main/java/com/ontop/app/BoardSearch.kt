package com.ontop.app

import com.ontop.app.data.CardEntity
import com.ontop.app.data.checklistItems

/** Search the visible text of every active note, regardless of the selected topic. */
internal fun searchBoardCards(cards: List<CardEntity>, query: String): List<CardEntity> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return cards
    return cards.filter { card ->
        val content = buildString {
            append(card.title)
            append('\n')
            append(card.description)
            if (card.checklistJson != null) {
                runCatching { card.checklistItems() }.getOrDefault(emptyList()).forEach {
                    append('\n')
                    append(it.text)
                }
            }
        }
        terms.all { content.contains(it, ignoreCase = true) }
    }
}
