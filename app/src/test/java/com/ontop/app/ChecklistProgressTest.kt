package com.ontop.app

import com.ontop.app.data.Checklist
import com.ontop.app.data.ChecklistItem
import org.junit.Assert.*
import org.junit.Test

class ChecklistProgressTest {
    private fun items(vararg done: Boolean) = done.mapIndexed { index, value -> ChecklistItem("$index", "Пункт $index", value) }
    @Test fun emptyAndBlankItemsAreZero() {
        assertEquals(0, Checklist.progress(emptyList()))
        assertEquals(0, Checklist.progress(listOf(ChecklistItem(text = " \n", done = true))))
    }
    @Test fun percentagesRoundToNearestInteger() {
        assertEquals(33, Checklist.progress(items(true, false, false)))
        assertEquals(67, Checklist.progress(items(true, true, false)))
        assertEquals(50, Checklist.progress(items(true, false)))
        assertEquals(100, Checklist.progress(items(true, true)))
    }
    @Test fun insertionDeletionToggleAndReorderingShareOneCalculation() {
        val original = items(true, false)
        assertEquals(50, Checklist.progress(original.reversed()))
        assertEquals(33, Checklist.progress(original + ChecklistItem("new", "Новый")))
        assertEquals(100, Checklist.progress(original.take(1)))
        assertEquals(100, Checklist.progress(original.map { it.copy(done = true) }))
        assertEquals(0, Checklist.progress(original.map { it.copy(done = false) }))
    }
    @Test fun blankDraftsAreNotItemsAndIdsStayStable() {
        val original = items(true, false)
        assertEquals(original, Checklist.clean(original + ChecklistItem(text = " ")))
        assertEquals(original.map { it.id }, Checklist.clean(original).map { it.id })
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateIdsAreRejected() {
        Checklist.clean(listOf(ChecklistItem("same", "А"), ChecklistItem("same", "Б")))
    }
}
