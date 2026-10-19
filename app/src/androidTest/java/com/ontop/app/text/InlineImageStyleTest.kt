package com.ontop.app.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardImageStore
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

internal fun inlineImages() = SemanticsMatcher("Inline document image") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("inline-image-") == true
}

class InlineImageStyleTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(width: Int, height: Int): File {
        val file = CardImageStore(context).resolve("images/${UUID.randomUUID()}.jpg")
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(90, 128, 106))
            paint.color = Color.rgb(210, 155, 95)
            drawRect(width / 2f, 0f, width.toFloat(), height / 2f, paint)
            paint.color = Color.rgb(95, 140, 180)
            drawRect(0f, height / 2f, width / 2f, height.toFloat(), paint)
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 12f
            drawRect(12f, 12f, width - 12f, height - 12f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 30f
            drawText("${width} x $height", 28f, height / 2f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureInlineImages") != "true") return
        val directory = File(context.cacheDir, "inline-image-style").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun threeAspectRatiosKeepFullWidthAndFitInLightAndDarkThemes() {
        val sizes = listOf(800 to 400, 400 to 600, 600 to 600)
        val files = sizes.map { fixture(it.first, it.second) }
        var selected by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        try {
            compose.setContent { OnTopTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("Абзац перед изображением")
                    Spacer(Modifier.height(12.dp))
                    NoteMediaView(NoteMedia("ratio", 0, MediaKind.IMAGE, path = "images/${files[selected].name}"))
                    Spacer(Modifier.height(12.dp))
                    Text("Абзац после изображения")
                }
            } } }
            for (theme in listOf(false, true)) for (index in sizes.indices) {
                compose.runOnIdle { selected = index; dark = theme }
                val expected = sizes[index].first.toFloat() / sizes[index].second
                compose.waitUntil(5_000) {
                    val bounds = compose.onNodeWithTag("inline-image-ratio").fetchSemanticsNode().boundsInRoot
                    kotlin.math.abs(bounds.width / bounds.height - expected) < .02f
                }
                val image = compose.onNodeWithTag("inline-image-ratio").fetchSemanticsNode().boundsInRoot
                val block = compose.onNodeWithTag("media-ratio").fetchSemanticsNode().boundsInRoot
                assertEquals(block.width, image.width, .5f)
                assertEquals(block.height, image.height, .5f)
                compose.onNodeWithText("Открыть изображение").assertDoesNotExist()
                compose.onNodeWithText("Удалить изображение из текста").assertDoesNotExist()
                capture("${if (theme) "dark" else "light"}-$index")
            }
        } finally { files.forEach { it.delete() } } // Only this test's unique, unsaved fixtures.
    }
    @Test fun editorLongPressMenuDeletesWithoutOpeningAndViewerTapOpensFullScreen() {
        val file = fixture(800, 400)
        var editing by mutableStateOf(true)
        var deleted = 0
        try {
            compose.setContent { OnTopTheme { Surface { Column {
                Text("До")
                NoteMediaView(NoteMedia("menu", 0, MediaKind.IMAGE, path = "images/${file.name}"),
                    onRemove = if (editing) ({ deleted++ }) else null)
                Text("После")
            } } } }
            compose.onNodeWithText("Удалить изображение из текста").assertDoesNotExist()
            compose.onNodeWithTag("inline-image-menu").performTouchInput { longClick() }
            compose.onNodeWithTag("full-note-image").assertDoesNotExist()
            compose.onNodeWithText("Удалить изображение из текста").performClick()
            compose.runOnIdle { assertEquals(1, deleted); editing = false }
            compose.onNodeWithTag("inline-image-menu").performClick()
            compose.onNodeWithTag("full-note-image").assertIsDisplayed()
            compose.onNodeWithText("Закрыть изображение").performClick()
            compose.onNodeWithTag("full-note-image").assertDoesNotExist()
            compose.onNodeWithText("До").assertIsDisplayed()
            compose.onNodeWithText("После").assertIsDisplayed()
        } finally { file.delete() }
    }
}
