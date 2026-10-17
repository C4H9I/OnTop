package com.ontop.app

import android.app.Application
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.ontop.app.data.CardCommentEntity
import com.ontop.app.data.CardEntity
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CardDetailsDialogTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: BoardViewModel

    @OptIn(ExperimentalLayoutApi::class)
    @SdkSuppress(minSdkVersion = 30)
    @Test fun realDialogKeepsComposerAboveImeAndUpdatesRoomWithoutTakingOverReading() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        fun screenshot(name: String) {
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: return
            val file = File(output, "design-review/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val originalKeyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        check(originalKeyboardSetting in listOf("0", "1", "null"))
        val automation = instrumentation.uiAutomation
        val originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var imeVisible = false
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val id = db.cardDao().insert(CardEntity(title = "Важное рядом", description = "Подробный текст",
                createdAt = 1, updatedAt = 1, sortOrder = 0))
            val card = db.cardDao().findById(id)!!
            for (n in 1..30) db.cardCommentDao().insert(CardCommentEntity(
                cardId = id, text = "Запись $n", createdAt = n.toLong()))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            compose.setContent {
                val owner = remember {
                    object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
                val state by model.state.collectAsState()
                val visible = WindowInsets.isImeVisible
                SideEffect { imeVisible = visible }
                OnTopTheme {
                    CardDetailsDialog(card, model, owner.lifecycle, state, onDismiss = {}, onEdit = {})
                }
            }
            var imeLayout = ""
            try { compose.waitUntil(5_000) {
                compose.onAllNodesWithText("История размышлений · 30").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("comment-input").performClick().performTextInput("Новая запись")
            compose.waitUntil(5_000) { imeVisible }
            compose.onNodeWithTag("comment-input").assertIsDisplayed()
            compose.onNodeWithTag("send-comment").assertIsDisplayed()
            // A visible floating/collapsed IME can have zero bottom inset; require the full docked keyboard.
            var expansions = 0
            var lastExpansion = 0L
            compose.waitUntil(5_000) {
                var keyboardTop = 0
                var keyboardBottom = 0
                instrumentation.runOnMainSync {
                    val root = android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull {
                        it.hasWindowFocus() && it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                    }
                    if (root != null) {
                        keyboardBottom = root.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                        val location = IntArray(2)
                        root.getLocationOnScreen(location)
                        // Decor can already be resized by the IME. Use display bounds, not its reduced height.
                        keyboardTop = root.context.getSystemService(android.view.WindowManager::class.java)
                            .maximumWindowMetrics.bounds.bottom - keyboardBottom - location[1]
                        imeLayout = "root=${root.height}, inset=$keyboardBottom, keyboardTop=$keyboardTop"
                    }
                }
                if (keyboardBottom == 0 && expansions < 3 && android.os.SystemClock.uptimeMillis() - lastExpansion > 600) {
                    val imeRoot = automation.windows.firstOrNull {
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }?.root
                    val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                        nodes.add(node)
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                    }
                    imeRoot?.let(::visit)
                    val labeled = nodes.firstOrNull {
                        val label = "${it.text} ${it.contentDescription}".lowercase()
                        "show on-screen keyboard" in label
                    }
                    val showKeyboard = generateSequence(labeled) { it.parent }.firstOrNull { it.isClickable }
                        ?: nodes.filter { it.isClickable }.singleOrNull()
                    imeLayout += ", IME nodes=" + nodes.joinToString { "${it.text}/${it.contentDescription}/${it.viewIdResourceName}" }
                    showKeyboard?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    expansions++
                    lastExpansion = android.os.SystemClock.uptimeMillis()
                }
                keyboardBottom > 0 &&
                    compose.onNodeWithTag("comment-input").fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop + 1 &&
                    compose.onNodeWithTag("send-comment").fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop + 1
            } } catch (error: Throwable) {
                screenshot("details-keyboard-failure")
                throw AssertionError(imeLayout, error)
            }
            screenshot("details-keyboard")
            // Scroll after the IME has resized the viewport: this establishes the user's intent to follow.
            compose.onNodeWithTag("reflection-list").performScrollToIndex(31)
            compose.onNodeWithTag("send-comment").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Новая запись").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Новая запись").assertIsDisplayed()
            val firstSaved = db.cardCommentDao().observeComments(id).first().last()
            assertEquals("Новая запись", firstSaved.text)

            // Stay on an old entry while another submission commits.
            compose.onNodeWithTag("reflection-list").performScrollToIndex(2)
            compose.onNodeWithTag("comment-input").performTextInput("Ещё одна запись")
            compose.onNodeWithTag("send-comment").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("К последней записи").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Запись 1").assertIsDisplayed()
            val saved = db.cardCommentDao().observeComments(id).first()
            assertEquals(32, saved.size)
            assertEquals((1..30).map { "Запись $it" }, saved.take(30).map { it.text })
            compose.onNodeWithText("К последней записи").performClick()
            compose.onNodeWithText("Ещё одна запись").assertIsDisplayed()
            val last = saved.last()
            compose.onNodeWithTag("delete-comment-${last.id}").performClick()
            compose.onNodeWithText("Удалить комментарий?").assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(32, db.cardCommentDao().observeComments(id).first().size)
            compose.onNodeWithTag("delete-comment-${last.id}").performClick()
            compose.onNodeWithText("Удалить", substring = false).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Ещё одна запись").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(31, db.cardCommentDao().observeComments(id).first().size)
            assertEquals(card, db.cardDao().findById(id))
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
            if (originalKeyboardSetting == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $originalKeyboardSetting")
            instrumentation.runOnMainSync { if (::model.isInitialized) model.viewModelScope.cancel() }
            db.close()
        }
    }
}
