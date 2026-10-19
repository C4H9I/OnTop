package com.ontop.app

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardSize
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CardEditorFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: BoardViewModel

    @Test fun createEditDiscardSaveAndDeleteUseLiveRoomState() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val keyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        fun display() {
            if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("editor-settings-compact").performClick()
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Отображение").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Отображение").performClick()
        }
        fun done() { compose.onNodeWithText("Готово").performClick() }
        check(keyboardSetting in listOf("0", "1", "null"))
        fun awaitHiddenIme() {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                compose.waitUntil(5_000) {
                    var hidden = false
                    instrumentation.runOnMainSync {
                        hidden = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
                            .all { it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
                    }
                    hidden
                }
            }
        }
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            compose.setContent {
                val owner = remember {
                    object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("  Первая мысль  ")
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                awaitDockedKeyboard(compose, "editor-save")
                compose.onNodeWithTag("editor-save").assertIsDisplayed()
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                    val file = File(output, "design-review/editor-keyboard.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { instrumentation.uiAutomation.takeScreenshot()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            compose.onNodeWithTag("editor-description").performScrollTo().performTextInput("Подробности")
            // Scrolling a note must keep text entry active; settings explicitly end it.
            compose.onNodeWithTag("editor-fields").performTouchInput { swipeUp() }
            compose.onNodeWithTag("editor-description").assertIsFocused()
            display()
            compose.onNodeWithTag("size-NORMAL").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Широкая").performScrollTo().performClick()
            done()
            awaitHiddenIme()
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                val file = File(output, "design-review/editor-size-no-ime.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { instrumentation.uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithTag("editor-save").performTouchInput { doubleClick() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val original = db.cardDao().observeCards().first().single()
            assertEquals("Первая мысль", original.title)
            assertEquals("Подробности", original.description)
            assertEquals(CardSize.WIDE, original.size)
            compose.onNodeWithTag("card-${original.id}").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            display()
            compose.onNodeWithTag("size-WIDE").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("size-NORMAL").performClick()
            done()
            assertEquals(CardSize.WIDE, db.cardDao().findById(original.id)!!.size)
            compose.onNodeWithTag("editor-title").performTextReplacement("Несохранённая мысль")
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-details").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(original, db.cardDao().findById(original.id))
            compose.onNodeWithText("Редактировать").performClick()
            display()
            compose.onNodeWithTag("size-WIDE").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("size-NORMAL").performClick()
            done()
            compose.onNodeWithTag("editor-title").performTextReplacement("Обновлённая мысль")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val updated = db.cardDao().observeCards().first().single()
            assertEquals("Обновлённая мысль", updated.title)
            assertEquals(CardSize.NORMAL, updated.size)
            assertEquals(original.id, updated.id)
            assertEquals(original.createdAt, updated.createdAt)
            assertEquals(original.sortOrder, updated.sortOrder)
            compose.onNodeWithTag("card-${updated.id}").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithText("Другие настройки").performClick()
            compose.onNodeWithText("Удалить карточку").performScrollTo().performClick()
            compose.onNodeWithText("Удалить", substring = false).performClick()
            withTimeout(5_000) { db.cardDao().observeCards().first { it.isEmpty() } }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Здесь появятся ваши мысли").assertIsDisplayed()
        } finally {
            if (keyboardSetting == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $keyboardSetting")
            instrumentation.runOnMainSync { if (::model.isInitialized) model.viewModelScope.cancel() }
            db.close()
        }
    }
}
