package com.ontop.app.drawing

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

class DrawingShapeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun createEditNavigateHistoryAndReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = DrawingSession()
        val viewport = DrawingViewportState(600,800)
        var tool by mutableStateOf(ShapeType.LINE)
        var selected by mutableStateOf<String?>(null)
        instrumentation.runOnMainSync { session.open(context,UUID.randomUUID().toString(),null) }
        compose.waitUntil(5000) { session.ready }
        compose.setContent {
            DrawingCanvas(session,viewport,false,false,Color.RED,4f,null,{}, {},Modifier.fillMaxSize(),
                shapeTool=tool,selectedShapeId=selected,onSelectShape={ selected=it })
        }
        fun drag(a: Offset,b: Offset) {
            val t=viewport.value
            compose.onNodeWithTag("drawing-viewport").performTouchInput {
                down(t.screenPoint(a)); moveTo(t.screenPoint(b),200); up()
            }
        }
        ShapeType.entries.forEachIndexed { i,type ->
            compose.runOnIdle { tool=type; selected=null }
            drag(Offset(80f,100f+i*150),Offset(240f,180f+i*150))
            assertEquals(type,session.document.shapes.last().type)
        }
        assertEquals(4,session.document.shapes.size)
        assertTrue(session.document.operations.isEmpty())
        // Select the line shaft and move, then edit its end handle.
        compose.runOnIdle { tool=ShapeType.LINE; selected=null }
        drag(Offset(160f,140f),Offset(200f,160f))
        var line=session.document.shapes.first { it.type == ShapeType.LINE }
        assertEquals(120f,line.x1,.1f); assertEquals(120f,line.y1,.1f)
        drag(Offset(line.x2,line.y2),Offset(310f,210f))
        line=session.document.shapes.first { it.type == ShapeType.LINE }
        assertEquals(310f,line.x2,.1f)
        compose.runOnIdle { session.undo() }
        assertEquals(280f,session.document.shapes.first { it.type == ShapeType.LINE }.x2,.1f)
        compose.runOnIdle { session.redo(); session.shape(session.document.shapes.first { it.type == ShapeType.LINE }.copy(color=Color.BLUE,width=16f)) }
        val styled=session.document.shapes.first { it.type == ShapeType.LINE }
        assertEquals(Color.BLUE,styled.color); assertEquals(16f,styled.width)
        compose.runOnIdle { session.deleteShape(styled.id); session.undo() }
        assertEquals(styled,session.document.shapes.last())
        // Rectangle corner resizing uses original opposite corner.
        val rect=session.document.shapes.first { it.type == ShapeType.RECTANGLE }
        compose.runOnIdle { selected=rect.id }
        drag(Offset(rect.x2,rect.y2),Offset(290f,510f))
        assertEquals(290f,session.document.shapes.first { it.id==rect.id }.x2,.1f)
        compose.runOnIdle {
            viewport.navigate(2.5f,Offset(viewport.value.size.width/2,viewport.value.size.height/2),Offset(viewport.value.size.width/2,viewport.value.size.height/2))
            selected=null; tool=ShapeType.ARROW
        }
        drag(Offset(300f,350f),Offset(380f,420f))
        assertEquals(300f,session.document.shapes.last().x1,.1f)
        val before=session.document
        compose.onNodeWithTag("drawing-viewport").performTouchInput {
            down(0,center); moveTo(0,center+Offset(20f,0f),100)
            down(1,center+Offset(80f,0f)); moveTo(1,center+Offset(100f,0f),100)
            up(1); moveTo(0,center+Offset(40f,0f),100); up(0)
        }
        assertEquals(before,session.document)
        val path=DrawingStore.save(context,session.document)
        val images=CardImageStore(context)
        val loaded=DrawingStore.load(context,path)
        assertEquals(session.document.shapes,loaded.shapes)
        val bytes=images.resolve(path).readBytes()
        compose.runOnIdle { viewport.fit() }
        val second=DrawingStore.save(context,session.document)
        assertArrayEquals(bytes,images.resolve(second).readBytes())
        val bitmap=DrawingRenderer.bitmap(loaded)
        assertEquals(Color.BLUE,bitmap.getPixel(240,240)); bitmap.recycle()
        // Photo source, editable JSON, and archive round trip use the same shape model.
        val photo="images/${UUID.randomUUID()}.jpg"
        val sourceBitmap=android.graphics.Bitmap.createBitmap(600,800,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        images.resolve(photo).outputStream().use { sourceBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it) }; sourceBitmap.recycle()
        val originalBytes=images.resolve(photo).readBytes()
        val photoPath=DrawingStore.save(context,loaded.copy(backgroundImage=photo))
        assertArrayEquals(originalBytes,images.resolve(photo).readBytes())
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,com.ontop.app.data.OnTopDatabase::class.java).build()
        try {
            val rich=com.ontop.app.text.RichDocument("Image",media=listOf(com.ontop.app.text.NoteMedia("image",0,com.ontop.app.text.MediaKind.IMAGE,photoPath)))
            db.cardDao().insert(com.ontop.app.data.CardEntity(1,"Shapes",rich.text,createdAt=1,updatedAt=1,sortOrder=0,formatting=com.ontop.app.text.RichTextCodec.encode(rich)))
            val manager=com.ontop.app.backup.BackupManager(context,db,root=java.io.File(context.cacheDir,"shape-backup-${UUID.randomUUID()}"),refreshWidgets={})
            val bytes=java.io.ByteArrayOutputStream(); manager.create { bytes }; assertNull(manager.state.value.error)
            db.cardDao().deleteAll()
            manager.prepare { bytes.toByteArray().inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            val card=db.cardDao().findById(1)!!
            val restoredPath=com.ontop.app.text.RichTextCodec.decode(card.description,card.formatting).media.single().path!!
            val restored=DrawingStore.load(context,restoredPath)
            assertEquals(loaded.shapes,restored.shapes)
            assertArrayEquals(originalBytes,images.resolve(restored.backgroundImage!!).readBytes())
            compose.runOnIdle { session.shape(restored.shapes.first().copy(width=9f)) }
            assertEquals(9f,session.document.shapes.last().width)
        } finally { db.close() }
        val old=org.json.JSONObject(DrawingCodec.encode(loaded)).put("version",3).apply { remove("shapes") }
        assertTrue(DrawingCodec.decode(old.toString()).shapes.isEmpty())
        compose.runOnIdle { session.viewModelScope.cancel() }
    }
    @Test fun editorMenuStyleDeleteAndReopen() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        var key by mutableStateOf(UUID.randomUUID().toString())
        var source by mutableStateOf<String?>(null)
        var visible by mutableStateOf(true)
        var saved: String? = null
        compose.setContent { if (visible) DrawingEditor(key,source,{ visible=false },{ saved=it; visible=false }) }
        fun ready() = compose.waitUntil(5000) { compose.onAllNodesWithTag("drawing-canvas").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onNodeWithText("\u0424\u0438\u0433\u0443\u0440\u044b").performClick()
        compose.onNodeWithText(ShapeType.ARROW.label).performClick()
        compose.onNodeWithTag("drawing-canvas").performTouchInput {
            down(Offset(width*.2f,height*.3f)); moveTo(Offset(width*.7f,height*.5f),200); up()
        }
        compose.onNodeWithTag("drawing-color-\u0421\u0438\u043d\u0438\u0439").performClick()
        compose.onNodeWithTag("drawing-width").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(12f) }
        compose.onNodeWithText("\u0413\u043e\u0442\u043e\u0432\u043e").performClick()
        compose.waitUntil(10000) { saved != null }
        val path=saved!!
        val arrow=DrawingStore.load(context,path).shapes.single()
        assertEquals(ShapeType.ARROW,arrow.type); assertEquals(12f,arrow.width); assertEquals(0xFF1565C0.toInt(),arrow.color)
        compose.runOnIdle { key=UUID.randomUUID().toString(); source=path; saved=null; visible=true }
        ready()
        compose.onNodeWithText("\u0424\u0438\u0433\u0443\u0440\u044b").performClick()
        compose.onNodeWithText(ShapeType.ARROW.label).performClick()
        compose.onNodeWithTag("drawing-canvas").performTouchInput { click(Offset(width*.45f,height*.4f)) }
        compose.onNodeWithText("\u0423\u0434\u0430\u043b\u0438\u0442\u044c").performClick()
        compose.onNodeWithTag("drawing-undo").performClick()
        compose.onNodeWithText("\u0413\u043e\u0442\u043e\u0432\u043e").performClick()
        compose.waitUntil(10000) { saved != null }
        assertEquals(arrow,DrawingStore.load(context,saved!!).shapes.single())
    }

}
