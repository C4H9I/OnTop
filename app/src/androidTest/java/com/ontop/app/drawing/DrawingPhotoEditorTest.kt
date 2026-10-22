package com.ontop.app.drawing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.backup.BackupManager
import com.ontop.app.text.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DrawingPhotoEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun editOnlySelectedPhotoInstanceReopenAndCancelWithoutTouchingOriginal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val name = "photo-editor-${System.nanoTime()}"
        val root = File(app.cacheDir, name)
        val store = CardImageStore(app)
        val photo = "images/${UUID.randomUUID()}.jpg"
        val owned = mutableSetOf(photo)
        lateinit var model: BoardViewModel
        var initialized = false
        try {
            val image = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(210, 160, 90)) }
            val file = store.resolve(photo); file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
            val sourceBytes = file.readBytes()
            val rich = RichDocument("До\nПосле", media = listOf(NoteMedia("first", 3, MediaKind.IMAGE, photo), NoteMedia("second", 3, MediaKind.IMAGE, photo)))
            db.cardDao().insert(CardEntity(1, "Фото", rich.text, createdAt = 1, updatedAt = 1, sortOrder = 0, formatting = RichTextCodec.encode(rich)))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db,
                BackupManager(app, db, root = root, refreshWidgets = {}), BoardPreferences(app, name)) }
            initialized = true
            withTimeout(5000) { model.state.first { !it.loading } }
            compose.setContent { OnTopTheme { Box(Modifier.width(360.dp).fillMaxHeight()) { BoardScreen(model, LocalLifecycleOwner.current.lifecycle, null, {}) } } }
            compose.onNodeWithText("Фото").performClick()
            // Ordinary viewing still opens the full image.
            compose.onNodeWithTag("inline-image-first").performScrollTo().performClick()
            compose.onNodeWithTag("full-note-image").assertIsDisplayed()
            compose.onNodeWithText("Закрыть изображение").performClick()
            compose.onNodeWithText("Редактировать").performClick()
            fun open() {
                compose.onNodeWithTag("inline-image-first").performScrollTo().performClick()
                compose.onNodeWithText("Редактировать изображение").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("drawing-canvas").fetchSemanticsNodes().isNotEmpty() }
            }
            fun line() { compose.onNodeWithTag("drawing-canvas").performTouchInput {
                down(Offset(width * .2f, height * .5f)); moveTo(Offset(width * .8f, height * .5f), 300); up()
            } }
            open()
            val bounds = compose.onNodeWithTag("drawing-canvas").fetchSemanticsNode().boundsInRoot
            assertEquals(2f, bounds.width / bounds.height, .01f)
            compose.onNodeWithTag("drawing-color-Синий").performClick(); line()
            compose.onNodeWithTag("drawing-undo").performClick(); compose.onNodeWithTag("drawing-redo").performClick()
            compose.onNodeWithTag("drawing-text-tool").performClick()
            compose.onNodeWithTag("drawing-canvas").performTouchInput { click(Offset(width * .1f, height * .1f)) }
            compose.onNodeWithTag("drawing-text-input").performTextInput("Подпись к фото\nOnTop")
            compose.onNodeWithText("Добавить / сохранить").performClick()
            compose.onNodeWithText("Карандаш").performClick()
            compose.onNodeWithText("Очистить").performClick()
            compose.onNodeWithText("Очистить нарисованный слой?").assertIsDisplayed()
            compose.onAllNodesWithText("Очистить").onLast().performClick()
            compose.onNodeWithTag("drawing-undo").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("drawing-editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(10000) { model.state.value.cards.any { RichTextCodec.decode(it.description, it.formatting).media.first().path != photo } }
            val saved = db.cardDao().findById(1)!!
            val blocks = RichTextCodec.decode(saved.description, saved.formatting).media
            val result = blocks.first().path!!; owned += result
            assertEquals(photo, blocks[1].path); assertArrayEquals(sourceBytes, file.readBytes())
            assertEquals(1, DrawingStore.load(app, result).operations.size)
            assertEquals("Подпись к фото\nOnTop", DrawingStore.load(app, result).texts.single().text)
            compose.onNodeWithText("Фото").performClick(); compose.onNodeWithText("Редактировать").performClick()
            open(); line(); compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            assertEquals(result, RichTextCodec.decode(db.cardDao().findById(1)!!.description, db.cardDao().findById(1)!!.formatting).media.first().path)
            assertEquals(1, DrawingStore.load(app, result).operations.size)
            assertArrayEquals(sourceBytes, file.readBytes())
        } finally {
            if (initialized) model.viewModelScope.cancel()
            db.close(); owned.forEach { store.delete(it) }; app.deleteSharedPreferences(name); root.deleteRecursively()
        }
    }
}
