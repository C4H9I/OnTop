package com.ontop.app.drawing

import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.backup.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.util.zip.*

class DrawingStorageTest {
    @Test fun eraserVersionedFilesReopenAndBackupRestoreKeepEditableDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CardImageStore(context)
        val databaseName = "drawing-storage-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, OnTopDatabase::class.java, databaseName).build()
        val root = File(context.cacheDir, "drawing-test-${System.nanoTime()}")
        val owned = mutableSetOf<String>()
        try {
            val stroke = DrawingOperation("DRAW", listOf(DrawingPoint(100f, 200f), DrawingPoint(500f, 200f)), Color.BLUE, 24f)
            val eraser = DrawingOperation("ERASE", listOf(DrawingPoint(300f, 100f), DrawingPoint(300f, 300f)), width = 24f)
            val document = DrawingDocument(operations = listOf(stroke, eraser))
            assertEquals(document, DrawingCodec.decode(DrawingCodec.encode(document)))
            val bitmap = DrawingRenderer.bitmap(document)
            assertEquals(1200, bitmap.width); assertEquals(1600, bitmap.height)
            assertEquals(Color.BLUE, bitmap.getPixel(400, 400))
            assertEquals(Color.WHITE, bitmap.getPixel(600, 400)); bitmap.recycle()
            val path = DrawingStore.save(context, document); owned += path
            val bytes = store.resolve(path).readBytes()
            assertEquals(document, DrawingStore.load(context, path))
            val changed = document.copy(operations = document.operations + DrawingOperation("CLEAR"))
            val next = DrawingStore.save(context, changed); owned += next
            assertNotEquals(path, next); assertArrayEquals(bytes, store.resolve(path).readBytes())
            val rich = RichDocument("До\nПосле", media = listOf(NoteMedia("drawing", 3, MediaKind.IMAGE, path)))
            val card = CardEntity(1, "Рисунок", rich.text, createdAt = 1, updatedAt = 1, sortOrder = 0,
                formatting = RichTextCodec.encode(rich))
            db.cardDao().insert(card)
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, databaseName).build()
            assertEquals(card, db.cardDao().findById(1))
            assertEquals(document, DrawingStore.load(context, path))
            val manager = BackupManager(context, db, root = root, refreshWidgets = {})
            val output = ByteArrayOutputStream(); manager.create { output }; assertNull(manager.state.value.error)
            // Stage 16.1 archive: PNG + model v1, no source resource.
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
            }
            entries.keys.filter { it.endsWith(".drawing.json") }.forEach { name ->
                entries[name] = JSONObject(entries.getValue(name).toString(Charsets.UTF_8)).put("version", 1)
                    .apply { remove("backgroundImage") }.toString().toByteArray()
            }
            val oldManifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).put("version", 7)
            oldManifest.put("files", JSONArray().apply { entries.filterKeys { it != "manifest.json" }.forEach { (name, content) ->
                put(JSONObject().put("path", name).put("bytes", content.size).put("sha256", BackupFormat.digest(content)))
            } })
            entries["manifest.json"] = oldManifest.toString().toByteArray()
            val archiveV7 = ByteArrayOutputStream()
            ZipOutputStream(archiveV7).use { zip -> entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            } }
            db.cardDao().deleteAll()
            manager.prepare { archiveV7.toByteArray().inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            val restored = db.cardDao().findById(1)!!
            val restoredRich = RichTextCodec.decode(restored.description, restored.formatting)
            assertEquals(rich.text, restoredRich.text); assertEquals(3, restoredRich.media.single().offset)
            val restoredPath = restoredRich.media.single().path!!; owned += restoredPath
            assertNotEquals(path, restoredPath); assertEquals(document, DrawingStore.load(context, restoredPath))
            assertArrayEquals(bytes, store.resolve(restoredPath).readBytes())
            // Actual prior archive version, with no drawing resources.
            val legacy = BackupFormat.encode(BackupData(listOf(card.copy(formatting = null)), emptyList(), emptyList()))
            val manifest = JSONObject().put("format", "OnTop").put("version", 6).put("createdAt", 0)
                .put("files", JSONArray().put(JSONObject().put("path", "data.json").put("bytes", legacy.size).put("sha256", BackupFormat.digest(legacy))))
            val old = ByteArrayOutputStream()
            ZipOutputStream(old).use { zip -> listOf("manifest.json" to manifest.toString().toByteArray(), "data.json" to legacy).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
            } }
            manager.prepare { old.toByteArray().inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            assertEquals("До\nПосле", db.cardDao().findById(1)!!.description)
        } finally { db.close(); context.deleteDatabase(databaseName); owned.forEach { store.delete(it) }; root.deleteRecursively() }
    }
}
