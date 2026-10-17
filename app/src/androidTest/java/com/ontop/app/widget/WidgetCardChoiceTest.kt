package com.ontop.app.widget

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardImageStore
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WidgetCardChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localPhotoIsSampledAndUnavailablePhotosKeepPlaceholder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CardImageStore(context)
        val photo = "images/${UUID.randomUUID()}.jpg"
        val corrupt = "images/${UUID.randomUUID()}.jpg"
        val missing = "images/${UUID.randomUUID()}.jpg"
        store.resolve(photo).parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        store.resolve(photo).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        store.resolve(corrupt).writeText("not an image")
        try {
            compose.setContent {
                OnTopTheme {
                    Column {
                        listOf(photo, null, missing, corrupt).forEachIndexed { index, path ->
                            WidgetCardChoice(card(index + 1L, path), index == 0, true) {}
                        }
                    }
                }
            }
            fun images(view: View): List<ImageView> = when (view) {
                is ImageView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { images(view.getChildAt(it)) }
                else -> emptyList()
            }
            compose.waitUntil(10_000) {
                var loaded = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val views = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap { images(it) }
                    loaded = views.any { (it.drawable as? BitmapDrawable)?.bitmap?.let { image ->
                        image.width <= 256 && image.height <= 256 &&
                            android.graphics.Color.red(image.getPixel(0, 0)) > 200
                    } == true }
                }
                loaded
            }
            (1L..4L).forEach { compose.onNodeWithTag("widget-thumbnail-$it", useUnmergedTree = true).assertIsDisplayed() }
            compose.onNodeWithText("Изображение недоступно").assertDoesNotExist()
            compose.onNodeWithTag("widget-card-1").assertIsSelected()
        } finally {
            runBlocking { store.delete(photo); store.delete(corrupt) }
        }
    }

    @Test fun selectionChangesAtNarrowWidthAndLargeFontAndCanBeDisabled() {
        var selected by mutableLongStateOf(1)
        var enabled by mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OnTopTheme {
                    Column(Modifier.width(240.dp)) {
                        (1L..2L).forEach { id ->
                            WidgetCardChoice(card(id), selected == id, enabled) { selected = id }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("widget-card-1").assertIsSelected()
        compose.onNodeWithTag("widget-card-2").performClick().assertIsSelected()
        compose.onNodeWithTag("widget-card-1").assertIsNotSelected()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("widget-card-1").assertIsNotEnabled()
        assertEquals(2L, selected)
    }

    private fun card(id: Long, path: String? = null) = CardEntity(id = id,
        title = "Важная мысль $id, к которой хочется возвращаться", createdAt = 1,
        updatedAt = 1, sortOrder = id, imagePath = path)
}
