package com.ontop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.text.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class UiRefinementsTest(private val width: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "portrait-{0}dp")
        fun widths() = listOf(arrayOf(360), arrayOf(393))
    }
    @get:Rule val compose = createComposeRule()

    @Test fun detailsTagsWrapUpdateAndIndicatorStaysInteractiveWithoutHero() {
        var tags by mutableStateOf((1L..6).map { TagEntity(it, "Тема $it: мысли и наблюдения") })
        var edits = 0
        compose.setContent { OnTopTheme {
            Box(Modifier.width(width.dp).fillMaxHeight()) {
                CardDetailsContent(CardEntity(1, "Чистый заголовок", "Основной текст",
                    imagePath = "images/unavailable.jpg", createdAt = 1, updatedAt = 1, sortOrder = 0),
                    emptyList(), true, false, false, null, { _, _ -> }, { _, _ -> }, {}, {},
                    tags = tags, onPercentages = { edits++ })
            }
        } }
        compose.onNodeWithTag("details-hero").assertDoesNotExist()
        compose.onNodeWithTag("details-title").assertTextEquals("Чистый заголовок")
        val badge = compose.onNodeWithTag("details-status-1").fetchSemanticsNode().boundsInRoot
        val topics = compose.onNodeWithTag("details-status-1-topics").fetchSemanticsNode().boundsInRoot
        assertTrue(topics.left >= badge.right)
        tags.forEach { compose.onNodeWithText(it.name).assertIsDisplayed() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-refinements").apply { mkdirs() }
        File(directory, "details-$width.png").outputStream().use {
            compose.onNodeWithTag("card-details").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("details-status-1").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, edits) }
        compose.runOnIdle { tags = listOf(TagEntity(1, "Переименованная тема")) }
        compose.onNodeWithText("Переименованная тема").assertIsDisplayed()
        compose.onNodeWithText("Тема 2: мысли и наблюдения").assertDoesNotExist()
        compose.runOnIdle { tags = emptyList() }
        compose.onNodeWithTag("details-status-1-topics").assertDoesNotExist()
    }

    @Test fun clipboardButtonsPreserveSelectionStylesMediaAndProgressPreference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val controller = NativeRichController()
        // NativeRichEditor receives one text fragment; media belong to MultimediaEditor.
        val original = RichDocument("До 🧠\nПосле", listOf(RichRange(0, 2, RichKind.BOLD)))
        var document by mutableStateOf(original)
        compose.setContent { OnTopTheme { Column(Modifier.width(width.dp)) {
            RichFormatToolbar(document, controller, true)
            NativeRichEditor(document, { document = it }, true, controller, {}, minimumLines = 3)
        } } }
        compose.onNodeWithTag("editor-description").performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.runOnIdle { controller.select(0, 2) }
        compose.onNodeWithContentDescription("Копировать").performClick()
        compose.runOnIdle {
            assertEquals("До", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(0, controller.start); assertEquals(2, controller.end)
            clipboard.setPrimaryClip(ClipData.newPlainText("", "А\nБ🧠"))
            controller.select(3, 3)
        }
        compose.onNodeWithContentDescription("Вставить").performClick()
        compose.runOnIdle {
            assertEquals(original.text.substring(0, 3) + "А\nБ🧠" + original.text.substring(3), document.text)
            assertEquals(8, controller.start)
            assertTrue(document.has(RichKind.BOLD, 0, 2))
            assertTrue(controller.view!!.onTextContextMenuItem(android.R.id.undo))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(original.text, document.text)
            assertEquals(original.media, document.media)
            assertTrue(controller.view!!.onTextContextMenuItem(android.R.id.redo))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(document.text.contains("А\nБ🧠"))
            clipboard.setPrimaryClip(ClipData.newPlainText("", "Я")); controller.select(0, 2)
        }
        compose.onNodeWithContentDescription("Вставить").performClick()
        compose.runOnIdle {
            assertTrue(document.text.startsWith("Я "))
            assertEquals(1, controller.start)
            clipboard.setPrimaryClip(ClipData.newRawUri("", Uri.parse("content://unavailable/private")))
        }
        val before = document
        compose.onNodeWithContentDescription("Вставить").performClick()
        compose.runOnIdle { assertEquals(before, document); controller.select(1, 1) }
        compose.onNodeWithContentDescription("Копировать").assertIsNotEnabled()
        val name = "ui-refinements-${java.util.UUID.randomUUID()}"
        try {
            BoardPreferences(context, name).saveSort(BoardSort.PROGRESS)
            assertEquals(BoardSort.PROGRESS, BoardPreferences(context, name).loadSort())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun sliderShowsPlainTitlesAndTopicsOfCurrentNote() {
        val cards = listOf(CardEntity(1, "Первая", imagePath = "images/cover.jpg", createdAt = 1, updatedAt = 1, sortOrder = 0),
            CardEntity(2, "Вторая", createdAt = 2, updatedAt = 2, sortOrder = 1))
        val session = NoteSliderSession().apply { enter(cards) }
        compose.setContent { OnTopTheme { Box(Modifier.width(width.dp).fillMaxHeight()) {
            NoteSliderScreen(cards, session, {}, {}, {},
                tags = listOf(TagEntity(1, "Размышления"), TagEntity(2, "Развитие")),
                cardTags = listOf(CardTagCrossRef(1, 1), CardTagCrossRef(2, 2)))
        } } }
        compose.onNodeWithTag("slide-title-1").assertTextEquals("Первая")
        compose.onNodeWithText("Размышления").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("slide-title-1").captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(2, 2))
        compose.onNodeWithText("Далее").performClick()
        compose.onNodeWithTag("slide-title-2").assertTextEquals("Вторая")
        compose.onNodeWithText("Развитие").assertIsDisplayed()
        compose.onNodeWithText("Размышления").assertDoesNotExist()
        assertEquals("images/cover.jpg", cards[0].imagePath)
    }
}
