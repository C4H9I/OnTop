package com.ontop.app.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardImageStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DrawingZoomTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transformInvertsAnchorsBoundsAndResizeAcrossAspectRatios() {
        listOf(600 to 800, 400 to 200, 200 to 1600).forEach { (w, h) ->
            val fit = DrawingViewport(w, h, Size(900f, 1400f))
            val center = Offset(450f, 700f)
            val zoomed = fit.navigate(3f, center, center)
            val point = Offset(w * .43f, h * .61f)
            assertTrue((zoomed.documentPoint(zoomed.screenPoint(point)) - point).getDistance() < .001f)
            val anchor = center + Offset(if (w * zoomed.scale > 900f) 20f else 0f,
                if (h * zoomed.scale > 1400f) 10f else 0f)
            val anchored = zoomed.navigate(1.1f, anchor, anchor)
            assertTrue((anchored.documentPoint(anchor) - zoomed.documentPoint(anchor)).getDistance() < .001f)
            val capped = zoomed.navigate(100f, center, center + Offset(100000f, -100000f))
            assertEquals(5f, capped.zoom)
            val imageCenter = capped.screenPoint(Offset(w / 2f, h / 2f))
            if (w * capped.scale >= 900f) {
                assertTrue(imageCenter.x + w * capped.scale / 2 >= 899.99f)
                assertTrue(imageCenter.x - w * capped.scale / 2 <= .01f)
            } else assertEquals(450f, imageCenter.x, .001f)
            val reset = capped.navigate(.001f, center, center)
            assertEquals(1f, reset.zoom); assertEquals(Offset.Zero, reset.pan)
            val resized = zoomed.resized(Size(1400f, 900f))
            assertTrue((resized.documentPoint(Offset(700f, 450f)) - Offset(w / 2f, h / 2f)).getDistance() < .001f)
        }
    }

    @Test fun pinchPanDrawingErasingTextAndExportUseDocumentCoordinates() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val images = CardImageStore(context)
        val photo = "images/${UUID.randomUUID()}.jpg"
        val owned = mutableSetOf(photo)
        val session = DrawingSession()
        val viewport = DrawingViewportState(400, 200)
        var eraser by mutableStateOf(false)
        var textTool by mutableStateOf(false)
        var selected by mutableStateOf<String?>(null)
        var editRequests = 0
        try {
            val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(210, 160, 90)) }
            images.resolve(photo).apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            val originalBytes = images.resolve(photo).readBytes()
            val caption = DrawingText("text", "Text", 170f, 60f, 16f)
            val source = DrawingStore.save(context, DrawingDocument(400, 200, backgroundImage = photo, texts = listOf(caption)))
            owned += source
            instrumentation.runOnMainSync { session.open(context, UUID.randomUUID().toString(), source) }
            compose.waitUntil(5000) { session.ready }
            val original = session.document
            compose.setContent {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TextButton(onClick = viewport::fit, modifier = Modifier.testTag("fit")) { Text("Вписать в экран") }
                    DrawingCanvas(session, viewport, eraser, textTool, Color.BLUE, 16f, selected,
                        { selected = it }, { editRequests++ }, Modifier.weight(1f).fillMaxWidth())
                }
            }
            fun zoom(factor: Float) {
                compose.onNodeWithTag("drawing-viewport").performTouchInput {
                    val radius = width * .1f
                    down(0, center - Offset(radius, 0f)); down(1, center + Offset(radius, 0f))
                    updatePointerTo(0, center - Offset(radius * factor, 0f))
                    updatePointerTo(1, center + Offset(radius * factor, 0f)); move(200)
                    up(1); moveTo(0, center, 100); up(0)
                }
            }
            zoom(2.5f)
            assertEquals(2.5f, viewport.value.zoom, .001f)
            assertEquals(original, session.document); assertFalse(session.canUndo)
            compose.onNodeWithTag("drawing-viewport").performTouchInput {
                down(0, center - Offset(80f, 0f)); down(1, center + Offset(80f, 0f))
                updatePointerTo(0, center - Offset(180f, 0f)); updatePointerTo(1, center - Offset(20f, 0f)); move(200)
                up(0); up(1)
            }
            assertTrue(viewport.value.pan.x < -90f)
            assertEquals(original, session.document); assertFalse(session.canUndo)
            fun stroke(a: Offset, b: Offset) {
                val transform = viewport.value
                compose.onNodeWithTag("drawing-viewport").performTouchInput {
                    down(transform.screenPoint(a)); moveTo(transform.screenPoint(b), 200); up()
                }
            }
            stroke(Offset(180f, 100f), Offset(240f, 100f))
            val line = session.document.operations.single()
            assertEquals(180f, line.points.first().x, .1f); assertEquals(100f, line.points.first().y, .1f)
            assertEquals(240f, line.points.last().x, .1f); assertEquals(16f, line.width)
            // Reach the original image boundary and check the inverse transform close to its edge.
            compose.runOnIdle { viewport.navigate(1f, Offset.Zero, Offset(100000f, 0f)) }
            stroke(Offset(2f, 130f), Offset(20f, 130f))
            assertEquals(2f, session.document.operations.last().points.first().x, .1f)
            assertEquals(130f, session.document.operations.last().points.first().y, .1f)
            compose.runOnIdle {
                viewport.fit()
                val center = Offset(viewport.value.size.width / 2, viewport.value.size.height / 2)
                viewport.navigate(2.5f, center, center)
            }
            compose.runOnIdle { eraser = true }
            stroke(Offset(210f, 90f), Offset(210f, 110f))
            assertEquals("ERASE", session.document.operations.last().type)
            // A second finger after drawing has already started must discard the whole draft.
            val beforeNavigation = session.document
            compose.onNodeWithTag("drawing-viewport").performTouchInput {
                down(0, center); moveTo(0, center + Offset(20f, 0f), 100)
                down(1, center + Offset(80f, 0f)); moveTo(1, center + Offset(90f, 0f), 100)
                up(1); moveTo(0, center + Offset(30f, 0f), 100); up(0)
            }
            assertEquals(beforeNavigation, session.document)
            compose.runOnIdle { textTool = true; eraser = false }
            stroke(Offset(174f, 64f), Offset(194f, 79f))
            val moved = session.document.texts.single()
            assertEquals(190f, moved.x, .1f); assertEquals(75f, moved.y, .1f); assertEquals(16f, moved.fontSize)
            compose.runOnIdle { session.undo() }
            assertEquals(caption, session.document.texts.single())
            compose.runOnIdle { session.redo() }
            assertEquals(moved, session.document.texts.single()); assertEquals(0, editRequests)
            val beforeTextNavigation = session.document
            val textPoint = viewport.value.screenPoint(Offset(moved.x + 4f, moved.y + 4f))
            compose.onNodeWithTag("drawing-viewport").performTouchInput {
                down(0, textPoint); moveTo(0, textPoint + Offset(20f, 0f), 100)
                down(1, textPoint + Offset(80f, 0f)); moveTo(1, textPoint + Offset(90f, 0f), 100)
                up(1); up(0)
            }
            assertEquals(beforeTextNavigation, session.document)
            val zoomedSave = DrawingStore.save(context, session.document); owned += zoomedSave
            val zoomedBytes = images.resolve(zoomedSave).readBytes()
            zoom(.1f)
            assertEquals(1f, viewport.value.zoom); assertEquals(Offset.Zero, viewport.value.pan)
            zoom(3f)
            compose.onNodeWithTag("fit").performClick()
            assertEquals(1f, viewport.value.zoom); assertEquals(Offset.Zero, viewport.value.pan)
            val fittedSave = DrawingStore.save(context, session.document); owned += fittedSave
            assertArrayEquals(zoomedBytes, images.resolve(fittedSave).readBytes())
            val restored = DrawingStore.load(context, fittedSave)
            assertEquals(session.document.operations, restored.operations); assertEquals(session.document.texts, restored.texts)
            assertArrayEquals(originalBytes, images.resolve(photo).readBytes())
            val output = BitmapFactory.decodeFile(images.resolve(fittedSave).path)
            val baseline = BitmapFactory.decodeFile(images.resolve(photo).path)
            assertEquals(400, output.width); assertEquals(200, output.height)
            assertEquals(Color.BLUE, output.getPixel(190, 100))
            assertEquals(baseline.getPixel(210, 100), output.getPixel(210, 100))
            output.recycle(); baseline.recycle()
        } finally { session.viewModelScope.cancel(); owned.forEach { images.delete(it) } }
    }
}
