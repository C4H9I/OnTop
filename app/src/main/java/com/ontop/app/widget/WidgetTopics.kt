package com.ontop.app.widget

import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardTagCrossRef

/** An exhausted filter remains a filter; only an explicit user choice enables all notes. */
internal fun rotationCandidates(cards: List<CardEntity>, links: List<CardTagCrossRef>,
    tagIds: Set<Long>, filterEnabled: Boolean): List<CardEntity> {
    val matching = if (filterEnabled) links.asSequence().filter { it.tagId in tagIds }
        .map { it.cardId }.toSet() else emptySet()
    return cards.filter { it.status == com.ontop.app.data.CardStatus.ACTIVE && (!filterEnabled || it.id in matching) }.distinctBy { it.id }
}
