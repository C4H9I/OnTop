package com.ontop.app.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardImageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DrawingCropTest {
    @Test fun freeAndFixedFramesStayInsideImageAndCanMove() {
        val start = CropFrame(20f, 20f, 180f, 100f)
        val narrower = DrawingCrop.drag(start, CropHandle.LEFT, 40f, 0f, 200, 120, null)
        assertEquals(120f, narrower.width, .01f)
        assertEquals(80f, narrower.height, .01f)
        val shorter = DrawingCrop.drag(narrower, CropHandle.BOTTOM, 0f, -30f, 200, 120, null)
        assertEquals(50f, shorter.height, .01f)
        val both = DrawingCrop.drag(shorter, CropHandle.TOP_LEFT, 20f, 10f, 200, 120, null)
        assertEquals(100f, both.width, .01f)
        assertEquals(40f, both.height, .01f)
        val moved = DrawingCrop.drag(both, CropHandle.MOVE, 500f, 500f, 200, 120, null)
        assertEquals(200f, moved.right, .01f)
        assertEquals(120f, moved.bottom, .01f)
        for (ratio in listOf(1f, 4f / 3f, 16f / 9f, 200f / 120f)) {
            val frame = start.aspect(ratio)
            assertEquals(ratio, frame.width / frame.height, .01f)
            val resized = DrawingCrop.drag(frame, CropHandle.BOTTOM_RIGHT, 30f, 15f, 200, 120, ratio)
            assertEquals(ratio, resized.width / resized.height, .01f)
            assertTrue(resized.left >= 0f && resized.top >= 0f && resized.right <= 200f && resized.bottom <= 120f)
        }
    }

    @Test fun cropSaveReopenAndContinueEditingKeepOriginalPixelsAndLayerPositions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CardImageStore(context)
        val source = File(context.cacheDir, "crop-${System.nanoTime()}.png")
        val owned = mutableListOf<String>()
        try {
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            for (y in 0 until 300) for (x in 200 until 400) bitmap.setPixel(x, y, Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val original = store.import(Uri.fromFile(source)); owned += original
            val bytes = store.resolve(original).readBytes()
            val document = DrawingStore.open(context, original).copy(
                operations = listOf(DrawingOperation("DRAW", listOf(DrawingPoint(220f, 120f), DrawingPoint(360f, 120f)))),
                texts = listOf(DrawingText("inside", "Внутри", 230f, 140f), DrawingText("outside", "Снаружи", 0f, 0f)),
                shapes = listOf(DrawingShape("shape", ShapeType.RECTANGLE, 240f, 170f, 300f, 220f)))
            val cropped = DrawingCrop.apply(document, CropFrame(200f, 60f, 380f, 260f))
            assertEquals(180, cropped.width); assertEquals(200, cropped.height)
            assertEquals(SourceCrop(200, 60, 380, 260), cropped.sourceCrop)
            assertEquals(20f, cropped.operations.single().points.first().x, .01f)
            assertEquals("inside", cropped.texts.single().id)
            assertEquals(30f, cropped.texts.single().x, .01f)
            assertEquals(40f, cropped.shapes.single().x1, .01f)
            val path = DrawingStore.save(context, cropped); owned += path
            val reopened = DrawingStore.load(context, path)
            assertEquals(cropped.copy(backgroundImage = DrawingStore.sourcePath(path)), reopened)
            val rendered = BitmapFactory.decodeFile(store.resolve(path).path)
            assertEquals(180, rendered.width); assertEquals(200, rendered.height)
            assertTrue(Color.blue(rendered.getPixel(100, 30)) > Color.red(rendered.getPixel(100, 30)))
            rendered.recycle()
            val again = DrawingCrop.apply(reopened, CropFrame(20f, 10f, 160f, 190f))
            assertEquals(SourceCrop(220, 70, 360, 250), again.sourceCrop)
            val next = DrawingStore.save(context, again.copy(operations = again.operations +
                DrawingOperation("DRAW", listOf(DrawingPoint(30f, 30f))))); owned += next
            assertEquals(140, DrawingStore.load(context, next).width)
            assertArrayEquals(bytes, store.resolve(original).readBytes())
        } finally { owned.forEach { store.delete(it) }; source.delete() }
    }

    @Test fun largeLandscapeSourceIsDecodedByRegionAndOriginalRemainsUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CardImageStore(context)
        val source = "images/${java.util.UUID.randomUUID()}.jpg"
        var result: String? = null
        try {
            val file = store.resolve(source); file.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            val bytes = file.readBytes()
            val document = DrawingStore.open(context, source)
            val cropped = DrawingCrop.apply(document, CropFrame(500f, 500f, 2500f, 1500f))
            result = DrawingStore.save(context, cropped)
            val output = BitmapFactory.decodeFile(store.resolve(result).path)
            assertEquals(2000, output.width); assertEquals(1000, output.height)
            output.recycle()
            assertArrayEquals(bytes, file.readBytes())
        } finally { result?.let { store.delete(it) }; store.delete(source) }
    }
}
