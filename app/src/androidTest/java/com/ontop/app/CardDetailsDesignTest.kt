package com.ontop.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardCommentEntity
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardImageStore
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@RunWith(Parameterized::class)
class CardDetailsDesignTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}dp fontScale={1}")
        fun sizes() = listOf(arrayOf<Any>(320, 1f), arrayOf<Any>(320, 2f), arrayOf<Any>(280, 2f))
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OnTopTheme(darkTheme = false) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) { content() }
                }
            }
        }
    }

    private fun assertFits(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: context.getExternalFilesDir(null)!!
        val file = File(output, "design-review/details-$name-${width}dp-${fontScale}x.png")
        file.parentFile!!.mkdirs()
        val bitmap = compose.onNodeWithTag("card-details").captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun longDescriptionEmptyHistoryAddAndConfirmedDelete() {
        val card = CardEntity(id = 1, title = "Замечай то, что помогает возвращаться к важному",
            description = "Подробное описание без ограничения строк. ".repeat(35),
            createdAt = 1, updatedAt = 1, sortOrder = 0)
        var comments by mutableStateOf(emptyList<CardCommentEntity>())
        var edits = 0
        var closed = false
        show {
            CardDetailsContent(card, comments, true, false, false, null,
                onAddComment = { text, done ->
                    comments = listOf(CardCommentEntity(id = 7, cardId = 1, text = text, createdAt = 1_700_000_000_000))
                    done(7)
                }, onDeleteComment = { id, done -> comments = comments.filterNot { it.id == id }; done() },
                onDismiss = { closed = true }, onEdit = { edits++ })
        }
        compose.onNodeWithText("Редактировать").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        compose.onNodeWithContentDescription("Закрыть карточку").performClick()
        compose.runOnIdle { assertTrue(closed) }
        assertFits("card-description")
        screenshot("hero")
        compose.onNodeWithTag("reflection-list").performScrollToIndex(1)
        compose.onNodeWithText("История размышлений · 0").assertIsDisplayed()
        compose.onNodeWithText("Здесь сохранится история ваших размышлений. Добавьте первую запись в поле внизу.")
            .assertExists()
        compose.onNodeWithTag("send-comment").assertIsNotEnabled()
        compose.onNodeWithTag("comment-input").performTextInput("Моя первая запись")
        compose.onNodeWithTag("send-comment").performClick()
        // Showing the IME can leave the reader above the end; use the offered transition in that case.
        if (compose.onAllNodesWithText("К последней записи").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("К последней записи").performClick()
        }
        compose.onNodeWithText("Моя первая запись").assertIsDisplayed()
        compose.onNodeWithTag("comment-input").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(1_700_000_000_000))
        compose.onNodeWithText(date).assertExists()
        compose.onNodeWithTag("delete-comment-7").performClick()
        compose.onNodeWithText("Удалить комментарий?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(1, comments.size) }
        compose.onNodeWithTag("delete-comment-7").performClick()
        compose.onNodeWithText("Удалить", substring = false).performClick()
        compose.runOnIdle { assertTrue(comments.isEmpty()) }
        compose.onNodeWithTag("reflection-list").performScrollToIndex(1)
        compose.onNodeWithText("История размышлений · 0").assertExists()
    }

    @Test fun photoAndLongCommentStayReadableAndFailedSendPreservesDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = CardImageStore(context)
        val path = "images/${java.util.UUID.randomUUID()}.jpg"
        val file = images.resolve(path)
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        var error by mutableStateOf<String?>(null)
        var saving by mutableStateOf(false)
        val comment = CardCommentEntity(id = 10, cardId = 2, text = "Длинное размышление без обрезания. ".repeat(30), createdAt = 1)
        try {
            show {
                CardDetailsContent(CardEntity(id = 2, title = "Сохраняй внимание", imagePath = path,
                    createdAt = 1, updatedAt = 1, sortOrder = 0), listOf(comment), true, false, saving, error,
                    onAddComment = { _, _ -> error = "Не удалось сохранить комментарий" },
                    onDeleteComment = { _, _ -> }, onDismiss = {}, onEdit = {})
            }
            compose.waitUntil(5_000) {
                val image = compose.onNodeWithTag("details-hero").captureToImage().asAndroidBitmap()
                android.graphics.Color.red(image.getPixel(image.width / 2, 30)) > 245
            }
            val image = compose.onNodeWithTag("details-hero").captureToImage().asAndroidBitmap()
            assertTrue(android.graphics.Color.red(image.getPixel(image.width / 2, image.height - 12)) < 100)
            screenshot("photo")
            compose.onNodeWithTag("reflection-list").performScrollToIndex(2)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(comment.text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("comment-input").performTextInput("Не терять черновик")
            compose.onNodeWithTag("send-comment").performClick()
            compose.onNodeWithText("Не удалось сохранить комментарий").assertExists()
            compose.onNodeWithTag("comment-input").assertTextEquals("Не терять черновик")
            compose.runOnIdle { saving = true }
            compose.onNodeWithTag("send-comment").assertIsNotEnabled()
            compose.onNodeWithTag("comment-input").assertIsNotEnabled()
        } finally { file.delete() }
    }
}
