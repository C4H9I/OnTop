package com.ontop.app

import com.ontop.app.data.CardEntity

enum class BoardSort(val label: String) {
    MANUAL("Мой порядок"),
    IMPORTANCE("По важности — сначала важные"),
    PROGRESS("По готовности"),
    CREATED("По дате создания — сначала новые"),
    UPDATED("По дате изменения — сначала недавно изменённые")
}

/** Display order only: never writes sortOrder or changes the slider's session. */
internal fun sortedBoardCards(cards: List<CardEntity>, mode: BoardSort): List<CardEntity> {
    val manual = compareBy<CardEntity> { it.sortOrder }.thenBy { it.id }
    val comparator = when (mode) {
        BoardSort.PROGRESS -> compareByDescending<CardEntity> { it.progress ?: -1 }.then(manual)
        BoardSort.MANUAL -> manual
        BoardSort.IMPORTANCE -> compareByDescending<CardEntity> { it.importance }.then(manual)
        BoardSort.CREATED -> compareByDescending<CardEntity> { it.createdAt }.then(manual)
        BoardSort.UPDATED -> compareByDescending<CardEntity> { it.updatedAt }.then(manual)
    }
    return cards.sortedWith(comparator)
}
