package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoteStatisticsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sqlCountsAllTypesMaxOpeningAndLiveUpdatesWithoutLoadingHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        try {
            db.cardDao().insert(CardEntity(1, "Мысль", createdAt = 1, updatedAt = 99, sortOrder = 0))
            db.cardDao().insert(CardEntity(2, "Другая", createdAt = 1, updatedAt = 1, sortOrder = 1))
            val repository = NoteInteractionRepository(db)
            val flow = repository.observeStatistics(1)
            assertEquals(NoteInteractionStatistics(), flow.first())
            val observed = async(Dispatchers.IO) { withTimeout(5000) { flow.first { it.opened == 1L } } }
            db.noteInteractionDao().insert(NoteInteractionEntity(noteId = 1, eventType = "NOTE_OPENED", timestamp = 10))
            assertEquals(1L, observed.await().intentionalVisits)
            listOf("NOTE_OPENED" to 20L, "SLIDER_VIEWED" to 40L, "WIDGET_OPENED" to 30L,
                "COMMENT_ADDED" to 90L, "COMMENT_ADDED" to 91L, "NOTE_EDITED" to 100L).forEach { (type, time) ->
                db.noteInteractionDao().insert(NoteInteractionEntity(noteId = 1, eventType = type, timestamp = time))
            }
            db.noteInteractionDao().insert(NoteInteractionEntity(noteId = 2, eventType = "WIDGET_OPENED", timestamp = 1000))
            assertEquals(NoteInteractionStatistics(2, 1, 1, 2, 1, 7, 40), flow.first())
            assertEquals(4L, flow.first().intentionalVisits)
            assertEquals(99L, db.cardDao().findById(1)!!.updatedAt)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM card_comments").use {
                it.moveToFirst(); assertEquals(0L, it.getLong(0))
            }
        } finally { db.close() }
    }

    @Test fun narrowLargeFontContentScrollsAndShowsAllValues() = contentFits(360, 2f)
    @Test fun portrait393ContentScrollsAndShowsAllValues() = contentFits(393, 1f)

    private fun contentFits(width: Int, fontScale: Float) {
        compose.setContent { OnTopTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.width(width.dp).height(600.dp)) {
                    NoteStatisticsContent(NoteStatisticsState(NoteInteractionStatistics(1, 2, 3, 4, 5, 15, 0)), 99)
                }
            }
        } }
        compose.onNodeWithTag("statistics-opened").performScrollTo().assertTextEquals("1")
        compose.onNodeWithTag("statistics-visits").performScrollTo().assertTextEquals("6")
        val formatter = java.time.format.DateTimeFormatter.ofLocalizedDateTime(
            java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withZone(java.time.ZoneId.systemDefault())
        compose.onNodeWithTag("statistics-last-visit").performScrollTo()
            .assertTextEquals(formatter.format(java.time.Instant.ofEpochMilli(0)))
        compose.onNodeWithTag("statistics-last-edit").performScrollTo().assertIsDisplayed()
    }

    @Test fun toolbarActionsRemainReachableAt360WithLargeFont() {
        var opened = 0
        compose.setContent { OnTopTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    CardDetailsContent(CardEntity(1, "Мысль", createdAt = 1, updatedAt = 99, sortOrder = 0),
                        emptyList(), true, false, false, null, { _, _ -> }, { _, _ -> }, {}, {},
                        onShare = {}, onReminder = {}, onStatistics = { opened++ })
                }
            }
        } }
        listOf("Закрыть карточку", "Статистика заметки", "Поделиться", "Настроить напоминание", "Меню заметки")
            .forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHasClickAction() }
        compose.onNodeWithText("Редактировать").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Статистика заметки").performClick()
        assertEquals(1, opened)
    }

    @Test fun loadingAndFailureNeverPretendToBeZeroStatistics() {
        var state by mutableStateOf(NoteStatisticsState())
        var retries = 0
        compose.setContent { OnTopTheme { NoteStatisticsContent(state, 99, onRetry = { retries++ }) } }
        compose.onNodeWithText("Загрузка статистики…").assertIsDisplayed()
        compose.onNodeWithTag("statistics-opened").assertDoesNotExist()
        compose.runOnIdle { state = NoteStatisticsState(failed = true) }
        compose.onNodeWithText("Не удалось загрузить статистику").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("statistics-opened").assertDoesNotExist()
    }
}
