package com.ontop.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TimePicker
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.reminders.ReminderManager
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ReminderDetailsFlowTest {
    @get:Rule val compose = createComposeRule()

    @SdkSuppress(minSdkVersion = 29)
    @Test fun boardBellAndStatusOpenCurrentRuleSaveLiveAndKeepCardsIndependent() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        lateinit var model: BoardViewModel
        var modelCreated = false
        val cardIds = mutableListOf<Long>()
        fun screenshot() {
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: app.getExternalFilesDir(null)!!.absolutePath
            val file = java.io.File(output, "design-review/reminder-ui-failure.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun waitForSettings() {
            try {
                compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                screenshot()
                val count = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
                val roots = (0 until count).joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString() }
                throw AssertionError("Settings did not load:\n$roots", failure)
            }
        }
        fun timeLabel(time: LocalTime) = "Время: " + time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        fun denyNotificationPromptIfPresent() {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            // Never revoke a permission that was granted before the test.
            find(automation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        try {
            val first = db.cardDao().insert(CardEntity(title = "Первая карточка", description = "Описание первой",
                createdAt = 1, updatedAt = 1, sortOrder = 0)).also(cardIds::add)
            val second = db.cardDao().insert(CardEntity(title = "Вторая карточка", description = "Описание второй",
                createdAt = 1, updatedAt = 1, sortOrder = 1)).also(cardIds::add)
            // Reserve an isolated ID range: cancellation must not touch real user reminder IDs.
            db.reminderDao().insert(ReminderEntity(id = Long.MAX_VALUE - 100, cardId = first,
                hour = 17, minute = 45, enabled = true, createdAt = 1, updatedAt = 1))
            repeat(30) { db.cardCommentDao().insert(CardCommentEntity(cardId = first, text = "Запись $it", createdAt = it.toLong())) }
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            modelCreated = true
            compose.setContent {
                val owner = remember {
                    object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-$first").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("card-$first").performClick()
            compose.onNodeWithTag("reminder-settings").assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("История размышлений · 30").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reflection-list").performScrollToIndex(25)
            compose.onNodeWithTag("reminder-settings").assertIsDisplayed().performClick()
            waitForSettings()
            compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
            compose.onNodeWithText(timeLabel(LocalTime.of(17, 45))).assertIsDisplayed()
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("reflection-list").performScrollToIndex(0)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Напоминание выключено").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder-status").assertIsDisplayed().performClick()
            waitForSettings()
            compose.onNode(isToggleable()).assertIsOff()
            compose.onNodeWithText(timeLabel(LocalTime.of(17, 45))).assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()

            compose.onNodeWithTag("card-$second").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Напоминание выключено").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder-status").performClick()
            waitForSettings()
            compose.onNode(isToggleable()).assertIsOff()
            compose.onNodeWithText(timeLabel(LocalTime.of(9, 0))).assertIsDisplayed()
            assertNull(db.reminderDao().forCard(second))
            val chosen = LocalTime.now().plusHours(12).withSecond(0).withNano(0)
            compose.onNodeWithText(timeLabel(LocalTime.of(9, 0))).performClick()
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            var selectionError: Throwable? = null
            instrumentation.runOnMainSync {
                selectionError = runCatching {
                    val views = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                    val picker = views.filterIsInstance<TimePicker>().single()
                    picker.hour = chosen.hour
                    picker.minute = chosen.minute
                    views.filterIsInstance<Button>().single { it.id == android.R.id.button1 }.performClick()
                }.exceptionOrNull()
            }
            selectionError?.let { throw it }
            compose.onNodeWithText(timeLabel(chosen)).assertIsDisplayed()
            compose.onNode(isToggleable()).performClick().assertIsOn()
            assertNull(db.reminderDao().forCard(second))
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) {
                denyNotificationPromptIfPresent()
                runBlocking { db.reminderDao().forCard(second)?.enabled == true }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isEmpty()
            }
            if (compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Готово").performClick()
            val status = "Ежедневно в " + chosen.format(DateTimeFormatter.ofPattern("HH:mm"))
            compose.waitUntil(5_000) { compose.onAllNodesWithText(status).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder-status").assertIsDisplayed().performClick()
            waitForSettings()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText(timeLabel(chosen)).assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            val saved = db.reminderDao().forCard(second)!!
            assertEquals(chosen.hour, saved.hour)
            assertEquals(chosen.minute, saved.minute)
            assertNotNull(saved.nextAt)
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            compose.onNodeWithTag("card-$first").performClick()
            compose.onNodeWithTag("reminder-settings").performClick()
            waitForSettings()
            compose.onNode(isToggleable()).assertIsOff()
            compose.onNodeWithText(timeLabel(LocalTime.of(17, 45))).assertIsDisplayed()
        } catch (failure: Throwable) {
            screenshot()
            throw failure
        } finally {
            if (modelCreated) model.viewModelScope.cancel()
            val manager = ReminderManager(app, db)
            cardIds.forEach { id -> db.cardDao().findById(id)?.let { manager.deleteCard(it) } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            db.close()
        }
    }
}
