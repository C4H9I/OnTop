package com.ontop.app.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.backup.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.*

class DrawingPhotoTest {
    @Test fun photoAnnotationsKeepSourceCoordinatesAndSurviveRepeatedSaveAndRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CardImageStore(context)
        val root = File(context.cacheDir, "photo-drawing-${System.nanoTime()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val owned = mutableSetOf<String>()
        try {
            val external = File(root, "camera.jpg")
            val image = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.rgb(210, 160, 90))
            external.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
            ExifInterface(external.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            val original = store.import(Uri.fromFile(external)); owned += original
            val originalBytes = store.resolve(original).readBytes()
            val baseline = BitmapFactory.decodeFile(store.resolve(original).path)
            val document = DrawingStore.open(context, original)
            assertEquals(200, document.width); assertEquals(400, document.height)
            assertTrue(document.operations.isEmpty()); assertEquals(original, document.backgroundImage)
            // Opening/cancelling creates nothing and leaves the imported source unchanged.
            assertArrayEquals(originalBytes, store.resolve(original).readBytes())
            val draw = DrawingOperation("DRAW", listOf(DrawingPoint(20f, 200f), DrawingPoint(180f, 200f)), Color.BLUE, 16f)
            val erase = DrawingOperation("ERASE", listOf(DrawingPoint(100f, 180f), DrawingPoint(100f, 220f)), width = 24f)
            val caption = DrawingText("caption", "Привет\nHello 123", 10f, 20f, 32f, Color.RED, 0)
            val annotated = document.copy(operations = listOf(draw, erase), texts = listOf(caption))
            assertEquals(annotated, DrawingCodec.decode(DrawingCodec.encode(annotated)))
            val first = DrawingStore.save(context, annotated); owned += first
            val loaded = DrawingStore.load(context, first)
            assertEquals(annotated.operations, loaded.operations)
            assertEquals(annotated.texts, loaded.texts)
            assertEquals(DrawingStore.sourcePath(first), loaded.backgroundImage)
            assertArrayEquals(originalBytes, store.resolve(loaded.backgroundImage!!).readBytes())
            val composite = BitmapFactory.decodeFile(store.resolve(first).path)
            assertEquals(200, composite.width); assertEquals(400, composite.height)
            val pixels = IntArray(composite.width * composite.height)
            composite.getPixels(pixels, 0, composite.width, 0, 0, composite.width, composite.height)
            assertTrue(pixels.any { it == Color.RED })
            assertEquals(Color.BLUE, composite.getPixel(50, 200))
            assertEquals(baseline.getPixel(100, 200), composite.getPixel(100, 200)); composite.recycle()
            val second = DrawingStore.save(context, loaded); owned += second
            assertNotEquals(first, second)
            assertArrayEquals(store.resolve(first).readBytes(), store.resolve(second).readBytes())
            assertEquals(2, DrawingStore.load(context, second).operations.size)
            assertArrayEquals(originalBytes, store.resolve(original).readBytes())
            val cleared = DrawingStore.save(context, loaded.copy(operations = loaded.operations + DrawingOperation("CLEAR"), texts = emptyList())); owned += cleared
            val clean = BitmapFactory.decodeFile(store.resolve(cleared).path)
            val a = IntArray(200 * 400); val b = IntArray(a.size)
            baseline.getPixels(a, 0, 200, 0, 0, 200, 400); clean.getPixels(b, 0, 200, 0, 0, 200, 400)
            assertArrayEquals(a, b); baseline.recycle(); clean.recycle()
            val rich = RichDocument("До\nПосле", media = listOf(NoteMedia("image", 3, MediaKind.IMAGE, second)))
            db.cardDao().insert(CardEntity(1, "Фото", rich.text, createdAt = 1, updatedAt = 1, sortOrder = 0, formatting = RichTextCodec.encode(rich)))
            val manager = BackupManager(context, db, root = File(root, "backup"), refreshWidgets = {})
            val bytes = ByteArrayOutputStream(); manager.create { bytes }; assertNull(manager.state.value.error)
            db.cardDao().deleteAll()
            manager.prepare { bytes.toByteArray().inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            val card = db.cardDao().findById(1)!!
            val restored = RichTextCodec.decode(card.description, card.formatting).media.single().path!!; owned += restored
            val restoredDocument = DrawingStore.load(context, restored)
            assertEquals(annotated.operations, restoredDocument.operations)
            assertEquals(annotated.texts, restoredDocument.texts)
            assertEquals(DrawingStore.sourcePath(restored), restoredDocument.backgroundImage)
            assertArrayEquals(originalBytes, store.resolve(restoredDocument.backgroundImage!!).readBytes())
            val again = DrawingStore.save(context, restoredDocument); owned += again
            assertArrayEquals(store.resolve(second).readBytes(), store.resolve(again).readBytes())
            val v2 = JSONObject(DrawingCodec.encode(document)).put("version", 2).apply { remove("texts") }
            assertEquals(document, DrawingCodec.decode(v2.toString()))
            // The stage 16.1 model remains readable.
            val legacy = JSONObject(DrawingCodec.encode(DrawingDocument())).put("version", 1).apply { remove("backgroundImage") }
            assertEquals(DrawingDocument(), DrawingCodec.decode(legacy.toString()))
        } finally { db.close(); owned.forEach { store.delete(it) }; root.deleteRecursively() }
    }
}
