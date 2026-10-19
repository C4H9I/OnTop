package com.ontop.app

import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardTagCrossRef

internal fun filterCardsByTag(cards: List<CardEntity>, links: List<CardTagCrossRef>, tagId: Long?): List<CardEntity> {
    if (tagId == null) return cards
    val ids = links.filter { it.tagId == tagId }.mapTo(mutableSetOf()) { it.cardId }
    return cards.filter { it.id in ids }
}
