package com.ontop.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.reminders.ReminderManager
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NoteEditorFlowTest {
    @get:Rule val compose = createComposeRule()
    private fun expandSettings() {
        if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("editor-settings-compact").performClick()
        }
    }
    private fun capture(name: String) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val file = File(output, "design-review/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun reminderWriteFailureKeepsDraftAndRetrySavesExactlyOneNote() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        var created = false
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_rule BEFORE INSERT ON reminders BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            created = true
            compose.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Черновик после ошибки")
            compose.onNodeWithTag("editor-description").performTextInput("Текст не должен потеряться")
            expandSettings()
            compose.onNodeWithText("Напоминания").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Вт").performScrollTo().performClick()
            compose.onNodeWithText("Применить").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { model.state.value.error != null && !model.state.value.saving }
            compose.onNodeWithTag("card-editor").assertIsDisplayed()
            compose.onNodeWithTag("editor-title").assertTextContains("Черновик после ошибки")
            compose.onNodeWithTag("editor-save").assertIsEnabled()
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            assertTrue(db.reminderDao().all().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_rule")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            assertEquals("Текст не должен потеряться", db.cardDao().observeCards().first().single().description)
            assertEquals(125, db.reminderDao().all().single().daysMask)
            assertFalse(db.reminderDao().all().single().enabled)
        } catch (error: Throwable) { capture("note-editor-retry-failure"); throw error
        } finally {
            if (created) model.viewModelScope.cancel()
            db.close()
        }
    }

    @Test fun prepareReminderBeforeFirstSaveRestoreDraftEditAndDiscardWithoutRoomWrites() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val keyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        check(keyboardSetting in listOf("0", "1", "null"))
        lateinit var model: BoardViewModel
        var created = false
        fun waitSettings() { compose.waitUntil(5_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() } }
        fun declinePermission() {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            find(automation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            // Isolate all real OS alarms from user rules, including autogenerated IDs.
            val seed = db.cardDao().insert(CardEntity(title = "Прежняя карточка", description = "Сохранить", createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.cardCommentDao().insert(CardCommentEntity(cardId = seed, text = "Прежний комментарий", createdAt = 1))
            db.reminderDao().insert(ReminderEntity(id = Long.MAX_VALUE - 600, cardId = seed, hour = 17, minute = 45,
                enabled = false, createdAt = 1, updatedAt = 1))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            created = true
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val lifecycle = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model, lifecycle.lifecycle) }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Новая заметка с расписанием")
            val content = (1..90).joinToString("\n") { "Строка $it. Важная мысль." }
            compose.onNodeWithTag("editor-description").performTextInput(content)
            expandSettings()
            compose.onNodeWithText("Напоминания").performClick()
            waitSettings()
            compose.onNode(isToggleable()).assertIsOff().performClick()
            compose.onNodeWithText("Вт").performScrollTo().performClick()
            compose.onNodeWithText("Применить").performClick()
            assertEquals(1, db.cardDao().observeCards().first().size)
            assertEquals(1, db.reminderDao().all().size)
            assertFalse(db.reminderDao().all().single().enabled)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("editor-title").assertTextContains("Новая заметка с расписанием")
            expandSettings()
            compose.onNodeWithText("Напоминания").performClick()
            waitSettings()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText("Вт").performScrollTo().assertIsNotSelected()
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
            compose.onNodeWithText("Продолжить редактирование").performClick()
            compose.onNodeWithTag("editor-description").performScrollTo().performClick()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                awaitDockedKeyboard(compose, "editor-save")
                compose.onNodeWithTag("editor-settings-compact").assertIsDisplayed()
            }
            capture("note-editor-long-text")
            compose.onNodeWithTag("editor-save").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                declinePermission()
                // Android's permission window can temporarily leave no resumed Compose root.
                runBlocking { db.cardDao().observeCards().first().size == 2 }
            }
            compose.waitUntil(5_000) {
                runCatching { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }.getOrDefault(false)
            }
            val saved = db.cardDao().observeCards().first().single { it.id != seed }
            val rule = db.reminderDao().forCard(saved.id)!!
            assertEquals(content, saved.description)
            assertEquals(125, rule.daysMask)
            assertTrue(rule.enabled)
            assertNotNull(rule.nextAt)
            compose.onNodeWithTag("card-${saved.id}").performScrollTo().performClick()
            compose.onNodeWithText("Редактировать").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Напоминания").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Напоминания").performClick()
            waitSettings()
            compose.onNode(isToggleable()).assertIsOn().performClick()
            compose.onNodeWithText("Применить").performClick()
            assertTrue(db.reminderDao().forCard(saved.id)!!.enabled)
            // Reminder-only changes must trigger the unsaved changes warning.
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertTrue(db.reminderDao().forCard(saved.id)!!.enabled)
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithText("Напоминания").performClick()
            waitSettings()
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("Применить").performClick()
            compose.onNodeWithTag("editor-title").performTextReplacement("Обновлённая заметка")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            assertFalse(db.reminderDao().forCard(saved.id)!!.enabled)
            assertEquals("Обновлённая заметка", db.cardDao().findById(saved.id)!!.title)
            assertEquals("Прежняя карточка", db.cardDao().findById(seed)!!.title)
            assertEquals("Прежний комментарий", db.cardCommentDao().observeComments(seed).first().single().text)
        } catch (error: Throwable) { capture("note-editor-failure"); throw error
        } finally {
            if (created) model.viewModelScope.cancel()
            val manager = ReminderManager(app, db)
            db.cardDao().observeCards().first().forEach { manager.deleteCard(it) }
            db.close()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            if (keyboardSetting == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $keyboardSetting")
        }
    }
}
