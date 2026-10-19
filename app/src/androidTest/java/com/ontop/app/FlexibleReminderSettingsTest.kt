package com.ontop.app

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.reminders.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class FlexibleReminderSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun nativeViews() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun chooseTime(hour: Int, minute: Int) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            failure = runCatching {
                val views = nativeViews()
                views.filterIsInstance<TimePicker>().single().apply { this.hour = hour; this.minute = minute }
                views.filterIsInstance<Button>().single { it.id == android.R.id.button1 }.performClick()
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun timeLabel(hour: Int, minute: Int) = "Время: " + LocalTime.of(hour, minute)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    private fun capture(name: String) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val file = java.io.File(output, "design-review/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun enlargedFontKeepsScheduleControlsAndSaveAccessible() = runBlocking<Unit> {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        var created = false
        try {
            val id = db.cardDao().insert(CardEntity(title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            created = true
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    OnTopTheme { ReminderSettingsDialog(id, model) {} }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Сохранить").assertIsDisplayed()
            click("По интервалу")
            ReminderSchedule.INTERVALS.forEach { hours -> compose.onNodeWithText("$hours ч").performScrollTo().assertIsDisplayed() }
            capture("flexible-reminder-large-font-intervals")
            weekdayLabels.forEach { day -> compose.onNodeWithText(day).performScrollTo().assertIsDisplayed() }
            click("Добавить тихие часы")
            compose.onNodeWithText("Тишина до 08:00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Приостановить до…").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Сохранить").assertIsDisplayed().assertIsEnabled()
            capture("flexible-reminder-large-font-pause")
        } finally {
            if (created) model.viewModelScope.cancel()
            db.close()
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun daysTimesValidationDuplicatesIntervalsQuietPauseSaveReopenAndResume() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        var created = false
        var open by mutableStateOf(true)
        val tomorrow = LocalDate.now().plusDays(1)
        try {
            val id = db.cardDao().insert(CardEntity(title = "Мысль", createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.reminderDao().insert(ReminderEntity(id = Long.MAX_VALUE - 200, cardId = id, hour = 9, minute = 0,
                enabled = false, createdAt = 1, updatedAt = 1))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            created = true
            compose.setContent { OnTopTheme { if (open) ReminderSettingsDialog(id, model) { open = false } } }
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            click("Добавить время"); chooseTime(9, 0)
            compose.onAllNodesWithText(timeLabel(9, 0)).assertCountEquals(1)
            click("Убрать")
            compose.onNodeWithText("Сохранить").assertIsNotEnabled()
            click("Добавить время"); chooseTime(9, 0)
            click("Добавить время"); chooseTime(18, 0)
            weekdayLabels.forEach(::click)
            compose.onNodeWithText("Сохранить").assertIsNotEnabled()
            click("Пн"); click("Ср"); click("Пт")
            compose.onNodeWithText("Сохранить").assertIsEnabled()
            assertEquals("", db.reminderDao().forCard(id)!!.times) // Still only a draft.
            click("По интервалу")
            click("6 ч")
            click("Начало: 09:00"); chooseTime(21, 0)
            click("Конец: 21:00"); chooseTime(7, 0)
            click("Добавить тихие часы")
            click("Тишина с 22:00"); chooseTime(23, 0)
            click("Тишина до 08:00"); chooseTime(6, 0)
            click("Приостановить до…")
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                failure = runCatching {
                    val views = nativeViews()
                    views.filterIsInstance<DatePicker>().single().updateDate(tomorrow.year, tomorrow.monthValue - 1, tomorrow.dayOfMonth)
                    views.filterIsInstance<Button>().single { it.id == android.R.id.button1 }.performClick()
                }.exceptionOrNull()
            }
            failure?.let { throw it }
            chooseTime(12, 0)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(5_000) { !open }
            val saved = db.reminderDao().forCard(id)!!
            assertEquals("INTERVAL", saved.mode)
            assertEquals(21, saved.daysMask)
            assertEquals("540,1080", saved.times)
            assertEquals(6, saved.intervalHours)
            assertEquals(1260, saved.windowStart)
            assertEquals(420, saved.windowEnd)
            assertEquals(1380, saved.quietStart)
            assertEquals(360, saved.quietEnd)
            assertEquals(tomorrow.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), saved.pausedUntil)
            compose.runOnIdle { open = true }
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("По интервалу").assertIsSelected()
            capture("flexible-reminder-saved-interval")
            compose.onNodeWithText("6 ч").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Начало: 21:00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Конец: 07:00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Пн").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Вт").assertIsNotSelected()
            compose.onNodeWithText("Тишина с 23:00").performScrollTo().assertIsDisplayed()
            click("Возобновить")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(5_000) { !open }
            assertNull(db.reminderDao().forCard(id)!!.pausedUntil)
            assertEquals(saved.schedule(), db.reminderDao().forCard(id)!!.schedule())
        } finally {
            if (created) model.viewModelScope.cancel()
            db.close()
        }
    }
}
