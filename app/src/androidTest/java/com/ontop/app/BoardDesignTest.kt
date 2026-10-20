package com.ontop.app

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardImageStore
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.OnTopTheme
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Real Compose measurement and scroll tests, without opening the user's database. */
@RunWith(Parameterized::class)
class BoardDesignTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}dp fontScale={1}")
        fun sizes() = listOf(arrayOf<Any>(320, 1f), arrayOf<Any>(320, 2f), arrayOf<Any>(280, 2f))
    }

    private fun card(id: Long, title: String, size: CardSize = CardSize.NORMAL, image: String? = null) =
        CardEntity(id = id, title = title, size = size, createdAt = id, updatedAt = id,
            sortOrder = id, imagePath = image)

    private fun show(cards: List<CardEntity>, open: (CardEntity) -> Unit = {}, add: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OnTopTheme(darkTheme = false) {
                    Box(Modifier.width(width.dp).fillMaxHeight().testTag("design-root")) {
                        BoardContent(BoardState(cards = cards, loading = false), add, open)
                    }
                }
            }
        }
    }

    private fun assertTitleFits(title: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("Title must grow instead of being clipped", layouts.single().hasVisualOverflow)
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: context.getExternalFilesDir(null)!!
        val file = File(output, "design-review/$name-${width}dp-${fontScale}x.png")
        file.parentFile!!.mkdirs()
        val bitmap = compose.onNodeWithTag("design-root").captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mosaicKeepsOrderSpansAndScrollsThroughTwoHundredCards() {
        val cards = listOf(card(1, "Замечай хорошее"), card(2, "Дыши. Не спеши. Дай себе немного времени."),
            card(3, "Важное рядом", CardSize.WIDE).copy(formatting = RichTextCodec.encode(
                RichDocument(media = listOf(NoteMedia(offset = 0, kind = MediaKind.YOUTUBE,
                    video = YouTubeLink.parse("https://youtu.be/M7lc1UVf-VE"))))))) + (4L..200).map {
            card(it, "Мысль $it", if (it % 7 == 0L) CardSize.WIDE else CardSize.NORMAL)
        }
        var opened: Long? = null
        var added = false
        show(cards, open = { opened = it.id }, add = { added = true })
        val first = compose.onNodeWithTag("card-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("card-2").fetchSemanticsNode().boundsInRoot
        val wide = compose.onNodeWithTag("card-3").fetchSemanticsNode().boundsInRoot
        if (fontScale == 1f) {
            assertTrue(first.left < second.left)
            assertEquals(first.top, second.top, 1f)
            assertTrue(wide.width > first.width * 1.9f)
        } else {
            assertEquals(first.left, second.left, 1f)
            assertEquals(first.width, wide.width, 1f)
            assertTrue(second.top >= first.bottom)
        }
        assertTrue(wide.top >= maxOf(first.bottom, second.bottom))
        for (id in 1L..3L) {
            val tile = compose.onNodeWithTag("card-$id").fetchSemanticsNode().boundsInRoot
            val visual = compose.onNodeWithTag("tile-visual-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val badge = compose.onNodeWithTag("tile-status-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(tile.top, visual.top, 1f)
            assertEquals(tile.height, visual.height, 1f)
            assertTrue(badge.top >= visual.top && badge.right <= visual.right)
        }
        assertTitleFits(cards[0].title)
        assertTitleFits(cards[1].title)
        saveScreenshot("mosaic")
        compose.onNodeWithTag("card-1").performClick()
        compose.runOnIdle { assertEquals(1L, opened) }
        compose.onNodeWithContentDescription("Создать карточку").performClick()
        compose.runOnIdle { assertTrue(added) }
        compose.onNodeWithTag("card-board").performScrollToIndex(199)
        compose.onNodeWithTag("card-200").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(200L, opened) }
        compose.onNodeWithTag("card-board").performScrollToIndex(0)
        val returned = compose.onNodeWithTag("card-1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, returned.left, 1f)
        assertEquals(first.width, returned.width, 1f)
        assertEquals(first.height, returned.height, 1f)
    }

    @Test fun brightPhotoHasReadableOverlayAndMissingPhotoKeepsTitle() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = CardImageStore(context)
        val path = "images/${java.util.UUID.randomUUID()}.jpg"
        val file = images.resolve(path)
        file.parentFile!!.mkdirs()
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        source.eraseColor(AndroidColor.WHITE)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        source.recycle()
        try {
            show(listOf(card(1, "Сохраняй внимание", image = path),
                card(2, "Продолжай свой путь", image = "images/0000.jpg")))
            assertTitleFits("Сохраняй внимание")
            assertTitleFits("Продолжай свой путь")
            val indicator = compose.onNodeWithTag("tile-status-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val visual = compose.onNodeWithTag("tile-visual-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val tileBounds = compose.onNodeWithTag("card-1").fetchSemanticsNode().boundsInRoot
            val inset = 8 * context.resources.displayMetrics.density
            assertEquals("Photo starts at the tile top", tileBounds.top, visual.top, 1f)
            assertEquals("No extra indicator row", tileBounds.height, visual.height, 1f)
            assertEquals("Top inset", visual.top + inset, indicator.top, 1f)
            assertEquals("Right inset", visual.right - inset, indicator.right, 1f)
            assertTrue("Indicator overlays the photograph", indicator.bottom < visual.bottom)
            compose.waitUntil(timeoutMillis = 5_000) {
                val photo = compose.onNodeWithTag("tile-visual-1", useUnmergedTree = true).captureToImage().asAndroidBitmap()
                val top = photo.getPixel(photo.width / 2, 30)
                AndroidColor.red(top) > 245 && AndroidColor.green(top) > 245
            }
            saveScreenshot("photo-overlay")
            val tile = compose.onNodeWithTag("card-1").captureToImage().asAndroidBitmap()
            // Inside the rounded edge, below the text: even white photos must become dark.
            val pixel = tile.getPixel(tile.width / 2, tile.height - 12)
            assertTrue(AndroidColor.red(pixel) < 100 && AndroidColor.green(pixel) < 100)
            compose.onNodeWithTag("card-2").assertHasClickAction()
        } finally { images.delete(path) }
    }
}
