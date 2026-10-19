package com.ontop.app.text

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.BoardViewModel
import com.ontop.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaStorageTest {
    @Test fun reopenMediaDocumentAndReferenceAwareDeletionAndCancelledImport() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val name = "media-storage-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).build()
        val store = CardImageStore(app)
        val source = File(app.cacheDir, "source-${System.nanoTime()}.jpg")
        Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888).apply {
            source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
        }
        val path = store.import(Uri.fromFile(source))
        lateinit var model: BoardViewModel
        try {
            val document = RichDocument("text\nend").toggle(RichKind.BOLD, 0, 4).withTextColor(0, 4, "#477451")
                .insertMedia("body", 0, NoteMedia("image", 0, MediaKind.IMAGE, path))
                .insertMedia("image", 4, NoteMedia("video", 0, MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtube.com/live/M7lc1UVf-VE?start=31")!!))
            val id = db.cardDao().insert(CardEntity(title = "Документ", description = document.text, formatting = RichTextCodec.encode(document),
                createdAt = 1, updatedAt = 1, sortOrder = 0))
            val second = db.cardDao().insert(CardEntity(title = "Общая обложка", description = "", imagePath = path, createdAt = 1, updatedAt = 1, sortOrder = 1))
            db.close(); db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).build()
            val card = db.cardDao().findById(id)!!
            assertEquals(document, RichTextCodec.decode(card.description, card.formatting))
            val decoded = store.load(path, 100)
            assertTrue(maxOf(decoded.width, decoded.height) <= 125); decoded.recycle()
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            suspend fun delete(card: CardEntity) {
                val done = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync { model.delete(card) { done.complete(Unit) } }
                withTimeout(5000) { done.await() }
            }
            delete(card)
            assertTrue(store.resolve(path).exists()) // Another card still uses it as a cover.
            delete(db.cardDao().findById(second)!!)
            assertFalse(store.resolve(path).exists())
            val imported = CompletableDeferred<String>()
            instrumentation.runOnMainSync { model.importInlineImage(Uri.fromFile(source)) { imported.complete(it) } }
            val draft = withTimeout(5000) { imported.await() }
            withTimeout(5000) { model.state.first { !it.saving } }
            val cancelled = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.cancelImageEdit { cancelled.complete(Unit) } }
            withTimeout(5000) { cancelled.await() }
            assertFalse(store.resolve(draft).exists())
            assertTrue(db.cardDao().allCards().isEmpty())
            assertTrue(runCatching { store.load(path, 100) }.isFailure)
        } finally { model.viewModelScope.cancel(); db.close(); app.deleteDatabase(name); store.delete(path); source.delete() }
    }
}
