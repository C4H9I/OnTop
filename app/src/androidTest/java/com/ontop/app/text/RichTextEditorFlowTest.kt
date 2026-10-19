package com.ontop.app.text

import android.app.Application
import android.graphics.Typeface
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RichTextEditorFlowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { failure = runCatching(block).exceptionOrNull() }
        failure?.let { throw it }
    }
    private fun nativeEditor(): NoteEditText {
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        return WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<NoteEditText>().single { it.isAttachedToWindow }
    }
    private fun format(label: String) { compose.onNodeWithContentDescription(label).performScrollTo().performClick() }
    private fun capture(name: String) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val file = File(output, "design-review/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun selectionStylesRealEditableEditsDraftRestoreSaveReopenViewAndClear() = runBlocking<Unit> {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        var created = false
        val body = "Смысл и внимание\nПервый пункт\nВторой пункт"
        try {
            onMain { model = BoardViewModel(app, db) }; created = true
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Отдельный заголовок")
            val field = compose.onNodeWithTag("editor-description")
            field.performTextInput(body)
            field.performTextInputSelection(TextRange(0, 5))
            format("Жирный"); format("Курсив"); format("Зачёркнутый")
            onMain {
                val editor = nativeEditor()
                assertEquals(0, editor.selectionStart); assertEquals(5, editor.selectionEnd)
                val styles = editor.editableText.getSpans(0, 5, StyleSpan::class.java)
                assertTrue(styles.any { it.style == Typeface.BOLD })
                assertTrue(styles.any { it.style == Typeface.ITALIC })
            }
            field.performTextInputSelection(TextRange(2))
            field.performTextInput("XX")
            compose.waitForIdle()
            onMain {
                assertEquals(body.replaceRange(2, 2, "XX"), nativeEditor().text.toString())
                nativeEditor().editableText.delete(2, 4)
                assertEquals(body, nativeEditor().text.toString())
            }
            compose.waitForIdle()
            field.performTextInputSelection(TextRange(5, 0))
            format("Цвет текста")
            compose.onNodeWithContentDescription("Терракота").performScrollTo().performClick()
            compose.onNodeWithText("Готово").performScrollTo().performClick()
            format("Стиль абзаца")
            compose.onNodeWithText("Заголовок 1").performClick()
            val second = body.indexOf('\n') + 1
            field.performTextInputSelection(TextRange(second))
            format("Маркированный список")
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            field.assertTextContains(body)
            onMain {
                assertTrue(nativeEditor().editableText.getSpans(0, 5, StyleSpan::class.java).any { it.style == Typeface.ITALIC })
            }
            capture("rich-note-editor")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val saved = db.cardDao().observeCards().first().single()
            assertEquals(body, saved.description)
            assertEquals("Отдельный заголовок", saved.title)
            val rich = RichTextCodec.decode(saved.description, saved.formatting)
            assertTrue(rich.has(RichKind.BOLD, 0, 5))
            assertTrue(rich.has(RichKind.ITALIC, 0, 5))
            assertTrue(rich.has(RichKind.STRIKE, 0, 5))
            assertTrue(rich.has(RichKind.COLOR, 0, 5, "#BC6952"))
            assertTrue(rich.has(RichKind.HEADING1, 0, 0))
            assertTrue(rich.has(RichKind.BULLET, second, second))
            compose.onNodeWithTag("card-${saved.id}").performClick()
            compose.onNodeWithTag("card-description").assertTextContains("• Первый пункт", substring = true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("card-description").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().layoutInput.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
            capture("rich-note-details")
            compose.onNodeWithText("Редактировать").performClick()
            field.performClick().performTextInputSelection(TextRange(0, 5))
            format("Очистить форматирование")
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
            compose.onNodeWithText("Продолжить редактирование").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val cleared = db.cardDao().findById(saved.id)!!
            val document = RichTextCodec.decode(cleared.description, cleared.formatting)
            assertEquals(body, cleared.description)
            assertFalse(document.has(RichKind.BOLD, 0, 5))
            assertFalse(document.has(RichKind.HEADING1, 0, 0))
            assertTrue(document.has(RichKind.BULLET, second, second))
        } catch (error: Throwable) { capture("rich-note-failure"); throw error
        } finally { if (created) model.viewModelScope.cancel(); db.close() }
    }

    @Test fun editingLegacyPlainNotePreservesWhitespaceAndDoesNotInventFormatting() = runBlocking<Unit> {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        lateinit var model: BoardViewModel
        var created = false
        val original = "  Строка с отступом\n\nКонец\n"
        try {
            val id = db.cardDao().insert(CardEntity(title = "Старая заметка", description = original, createdAt = 1, updatedAt = 1, sortOrder = 0))
            onMain { model = BoardViewModel(app, db) }; created = true
            compose.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model, owner.lifecycle) }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("card-$id").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithTag("editor-description").assertTextContains(original)
            compose.onNodeWithTag("editor-title").performTextReplacement("Новый заголовок")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            assertEquals(original, db.cardDao().findById(id)!!.description)
            assertNull(db.cardDao().findById(id)!!.formatting)
        } finally { if (created) model.viewModelScope.cancel(); db.close() }
    }
}
