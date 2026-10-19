package com.ontop.app.widget

import com.ontop.app.data.CardEntity
import kotlin.random.Random

/** No history writes: the previous identity is the only exclusion. */
internal fun chooseRotationNote(cards: List<CardEntity>, currentId: Long?, random: Random = Random.Default): CardEntity? {
    val candidates = if (cards.size > 1) cards.filter { it.id != currentId } else cards
    if (candidates.isEmpty()) return null
    val total = candidates.sumOf { 10L + it.importance.coerceIn(0, 100) }
    var ticket = random.nextLong(total)
    for (card in candidates) {
        ticket -= 10L + card.importance.coerceIn(0, 100)
        if (ticket < 0) return card
    }
    error("Unreachable weighted choice")
}
