package com.ontop.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ReminderSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedTimeAndEnabledStateArePrefilledAndChangeOnlyOnSave() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        var modelCreated = false
        var open by mutableStateOf(true)
        try {
            val id = db.cardDao().insert(CardEntity(title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.reminderDao().insert(ReminderEntity(id = Long.MAX_VALUE - 3, cardId = id, hour = 17, minute = 45, enabled = true,
                createdAt = 1, updatedAt = 1))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            modelCreated = true
            compose.setContent { OnTopTheme { if (open) ReminderSettingsDialog(id, model) { open = false } } }
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            val formatted = LocalTime.of(17, 45).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            compose.onNodeWithText("Время: $formatted").assertIsDisplayed()
            compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
            assertTrue(db.reminderDao().forCard(id)!!.enabled)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(5_000) { !open }
            val saved = db.reminderDao().forCard(id)!!
            assertFalse(saved.enabled)
            assertEquals(17, saved.hour)
            assertEquals(45, saved.minute)
            assertNull(saved.nextAt)
        } finally {
            if (modelCreated) model.viewModelScope.cancel()
            db.close()
        }
    }
}
