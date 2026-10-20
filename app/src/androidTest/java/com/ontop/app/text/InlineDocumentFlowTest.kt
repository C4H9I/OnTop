package com.ontop.app.text

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.room.Room
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

class InlineDocumentFlowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun action(label: String) = compose.onNodeWithContentDescription(label).performScrollTo().performClick()
    private fun editors(): List<NoteEditText> {
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        return WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<NoteEditText>()
    }
    private fun typeInFocusedFragment(text: String, awaitTrailingFocus: Boolean = false) {
        if (awaitTrailingFocus) compose.waitUntil(5_000) {
            var ready = false
            compose.runOnIdle { ready = editors().lastOrNull { it.isShown }?.hasFocus() == true }
            ready
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, editors().count { it.hasFocus() }) }
        // Do not explicitly select the trailing field: production must put the cursor there.
        compose.onNode(isFocused() and hasSetTextAction()).performTextInput(text)
        compose.waitForIdle()
        compose.runOnIdle {
            val editor = editors().single { it.hasFocus() }
            assertTrue("Native input must be applied before the next toolbar action", editor.text.toString().contains(text))
            assertEquals("Native cursor after typing", text.length, editor.selectionStart)
        }
    }
    private fun screenshot(name: String) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "inline-document-check").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun assertViewOrder(root: String, document: RichDocument) {
        fun tags(node: SemanticsNode): List<String> = listOfNotNull(node.config.getOrNull(SemanticsProperties.TestTag)) + node.children.flatMap(::tags)
        val expected = document.pieces().flatMap { piece ->
            listOf("card-description-${piece.key}") + listOfNotNull(document.media.getOrNull(piece.index)?.let { "media-${it.id}" })
        }
        val actual = tags(compose.onNodeWithTag(root, useUnmergedTree = true).fetchSemanticsNode()).filter { it in expected }
        assertEquals(expected, actual)
    }

    @Test fun sequentialTypingStaysBetweenMediaThroughSaveViewSliderAndReopen() = runBlocking<Unit> {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val source = File(app.cacheDir, "inline-sequence-${System.nanoTime()}.jpg")
        Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
        }
        lateinit var model: BoardViewModel
        val imported = mutableSetOf<String>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                check(contract is ActivityResultContracts.PickVisualMedia)
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, Uri.fromFile(source) as O)
            }
        }
        try {
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) { OnTopTheme { BoardScreen(model, owner.lifecycle) } }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Последовательный документ")
            compose.onNodeWithTag("editor-description").performTextInput("Текст 1")
            compose.runOnIdle {
                val editor = editors().single { it.hasFocus() }
                assertEquals("Cursor after initial typing", 7, editor.selectionStart)
            }
            compose.onNodeWithContentDescription("Вставить изображение").performScrollTo()
            compose.runOnIdle {
                val editor = editors().single { it.hasFocus() }
                assertEquals("Cursor after toolbar scroll", 7, editor.selectionStart)
            }
            compose.onNodeWithContentDescription("Вставить изображение").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Текст после").fetchSemanticsNodes().size == 1 }
            compose.runOnIdle {
                assertTrue("Original field must shrink after first insertion", editors().all { it.minLines == 1 })
            }
            typeInFocusedFragment("Текст 2", awaitTrailingFocus = true)
            action("Вставить изображение")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Текст после").fetchSemanticsNodes().size == 2 }
            typeInFocusedFragment("Текст 3", awaitTrailingFocus = true)
            fun clipboardRoundTrip(expected: String) {
                compose.runOnIdle {
                    val editor = editors().single { it.hasFocus() }
                    editor.setSelection(0, editor.text.length)
                }
                action("Копировать")
                compose.runOnIdle {
                    val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
                action("Вставить")
                compose.runOnIdle {
                    val editor = editors().single { it.hasFocus() }
                    assertEquals(expected, editor.text.toString())
                    assertEquals(expected.length, editor.selectionStart)
                }
            }
            clipboardRoundTrip("Текст 3")
            action("Вставить видео")
            compose.onNodeWithText("Ссылка YouTube").performTextInput("https://youtu.be/M7lc1UVf-VE?t=12")
            compose.onNodeWithText("Вставить видео").performClick()
            typeInFocusedFragment("Текст 4", awaitTrailingFocus = true)
            clipboardRoundTrip("Текст 4")
            screenshot("sequence-editor")
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val saved = db.cardDao().observeCards().first().single()
            var document = RichTextCodec.decode(saved.description, saved.formatting)
            imported.addAll(document.imagePaths())
            assertEquals(listOf("Текст 1\n", "Текст 2\n", "Текст 3\n", "Текст 4"), document.pieces().map { document.fragment(it).text })
            assertEquals(listOf(MediaKind.IMAGE, MediaKind.IMAGE, MediaKind.YOUTUBE), document.media.map { it.kind })
            assertEquals(listOf(8, 16, 24), document.media.map { it.offset })
            assertNull(saved.imagePath)
            compose.onNodeWithTag("card-${saved.id}").performClick()
            assertViewOrder("reflection-list", document)
            screenshot("sequence-view")
            compose.onNodeWithContentDescription("Закрыть карточку").performClick()
            compose.onNodeWithContentDescription("Просмотр заметок").performClick()
            assertViewOrder("note-slider", document)
            compose.onNodeWithText("Редактировать").performClick()
            val imageA = document.media[0].id
            compose.onNodeWithTag("after-media-$imageA").performScrollTo().performClick()
            typeInFocusedFragment("Дополнительный абзац\n")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-slider").fetchSemanticsNodes().isNotEmpty() }
            val changed = db.cardDao().findById(saved.id)!!
            document = RichTextCodec.decode(changed.description, changed.formatting)
            assertEquals(listOf("Текст 1\n", "Дополнительный абзац\nТекст 2\n", "Текст 3\n", "Текст 4"), document.pieces().map { document.fragment(it).text })
            assertEquals(imported, document.imagePaths())
            assertViewOrder("note-slider", document)
            // The explicit before/after controls must select the appropriate native field/cursor.
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithTag("before-media-$imageA").performScrollTo().performClick()
            compose.runOnIdle {
                val focused = editors().single { it.hasFocus() }
                assertEquals("Текст 1\n", focused.text.toString())
                assertEquals(focused.text.length, focused.selectionStart)
            }
            compose.onNodeWithTag("inline-image-$imageA").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithTag("remove-media-$imageA").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-slider").fetchSemanticsNodes().isNotEmpty() }
            val deleted = db.cardDao().findById(saved.id)!!
            assertEquals(changed.description, deleted.description)
            val remaining = RichTextCodec.decode(deleted.description, deleted.formatting)
            assertEquals(document.media.drop(1), remaining.media)
            assertFalse(CardImageStore(app).resolve(document.media[0].path!!).exists())
        } catch (failure: Throwable) { screenshot("sequence-failure"); throw failure
        } finally {
            model.viewModelScope.cancel(); db.close(); source.delete()
            imported.forEach { CardImageStore(app).delete(it) }
        }
    }
}
