package com.ontop.app.text

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.backup.*
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.ontop.app.BoardViewModel
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AttachmentStorageTest {
    @Test fun filesSurviveSourceDeletionRoomReopenAndBackupRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "attachment-test-${System.nanoTime()}").apply { mkdirs() }
        val name = "attachments-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
        val store = CardImageStore(context)
        val paths = mutableSetOf<String>()
        try {
            val blocks = listOf("sample.pdf" to "%PDF-1.4\nfixture", "sample.txt" to "Document contents").map { (filename, text) ->
                val source = File(dir, filename).apply { writeText(text) }
                // Exercise the same content URI stream and metadata query used by SAF.
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.shares",
                    File(context.filesDir, "attachments/00000000-0000-0000-0000-000000000000.bin").apply {
                        parentFile!!.mkdirs(); writeBytes(source.readBytes())
                    }, filename)
                val block = AttachmentStore(context).import(uri)
                paths.add(block.path!!)
                File(context.filesDir, "attachments/00000000-0000-0000-0000-000000000000.bin").delete()
                source.delete()
                assertEquals(filename, block.name)
                assertEquals(text, store.resolve(block.path).readText())
                block
            }
            val doc = RichDocument("Before\nAfter", media = blocks.map { it.copy(offset = 7) })
            val id = db.cardDao().insert(CardEntity(title = "Files", description = doc.text,
                formatting = RichTextCodec.encode(doc), createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.close(); db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            val card = db.cardDao().findById(id)!!
            assertEquals(doc, RichTextCodec.decode(card.description, card.formatting))
            val archive = File(dir, "backup.ontop")
            archive.outputStream().use { BackupFormat.write(BackupData(listOf(card), emptyList(), emptyList()), store::resolve, it) }
            val restored = BackupFormat.read(archive, File(dir, "extracted"))
            assertEquals(card, restored.cards.single())
            paths.forEach { assertArrayEquals(store.resolve(it).readBytes(), File(dir, "extracted/$it").readBytes()) }
            val manager = BackupManager(context, db, root = File(dir, "manager"), refreshWidgets = {})
            manager.prepare { archive.inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            val reopened = db.cardDao().allCards().single()
            val files = RichTextCodec.decode(reopened.description, reopened.formatting).media
            assertEquals(2, files.size)
            files.forEachIndexed { index, block ->
                paths.add(block.path!!)
                assertEquals(blocks[index].name, block.name)
                assertTrue(store.resolve(block.path).isFile)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.shares", store.resolve(block.path), block.name!!)
                context.contentResolver.openInputStream(uri)!!.use { assertEquals(block.bytes, it.readBytes().size.toLong()) }
            }
            assertEquals(1, RichTextCodec.decode(reopened.description, reopened.formatting).removeMedia(files.first().id).media.size)
            assertFalse(AttachmentStore.validPath("attachments/../secret.bin"))
            lateinit var model: BoardViewModel
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { model = BoardViewModel(context.applicationContext as Application, db) }
            try {
                suspend fun complete(action: (() -> Unit) -> Unit) {
                    val done = CompletableDeferred<Unit>()
                    instrumentation.runOnMainSync { action { done.complete(Unit) } }
                    withTimeout(10000) { done.await() }
                }
                val imported = CompletableDeferred<List<NoteMedia>>()
                val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.shares", store.resolve(it.path!!), it.name!!) }
                instrumentation.runOnMainSync { model.importAttachments(uris) { imported.complete(it) } }
                val drafts = withTimeout(10000) { imported.await() }
                assertEquals(2, drafts.size)
                paths.addAll(drafts.map { it.path!! })
                complete { model.cancelImageEdit(it) }
                drafts.forEach { assertFalse(store.resolve(it.path!!).exists()) }
                complete { model.changeStatus(reopened, CardStatus.ARCHIVED, it) }
                files.forEach { assertTrue(store.resolve(it.path!!).exists()) }
                complete { model.changeStatus(reopened, CardStatus.TRASHED, it) }
                files.forEach { assertTrue(store.resolve(it.path!!).exists()) }
                val trashed = db.cardDao().findById(reopened.id)!!
                complete { model.permanentlyDelete(trashed, it) }
                files.forEach { assertFalse(store.resolve(it.path!!).exists()) }
            } finally { model.viewModelScope.cancel() }
        } finally {
            db.close(); context.deleteDatabase(name)
            paths.forEach { store.delete(it) }; dir.deleteRecursively()
        }
    }
}
