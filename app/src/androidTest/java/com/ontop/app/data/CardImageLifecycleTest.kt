package com.ontop.app.data

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.BoardViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CardImageLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private lateinit var model: BoardViewModel
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test
    fun replacementCancellationFailedSaveAndCardDeletionRespectFileOwnership() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val store = CardImageStore(app)
        val source = File(app.cacheDir, "lifecycle-${System.nanoTime()}.jpg")
        val paths = mutableSetOf<String>()
        suspend fun finish() = withTimeout(10_000) { model.state.first { !it.saving } }
        try {
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            val originalPath = store.import(Uri.fromFile(source)).also { paths.add(it) }
            val id = db.cardDao().insert(CardEntity(title = "Мысль", description = "Текст",
                createdAt = 1, updatedAt = 1, sortOrder = 0, imagePath = originalPath))
            val original = db.cardDao().observeCards().first().single()
            onMain { model = BoardViewModel(app, db); model.beginImageEdit(original) }
            onMain { model.importImage(Uri.fromFile(source)) }
            val cancelled = finish().draftImagePath!!.also { paths.add(it) }
            assertTrue(store.resolve(originalPath).exists())
            onMain { model.cancelImageEdit {} }
            finish()
            assertFalse(store.resolve(cancelled).exists())
            assertEquals(original, db.cardDao().observeCards().first().single())

            onMain { model.beginImageEdit(original); model.importImage(Uri.fromFile(source)) }
            val replacement = finish().draftImagePath!!.also { paths.add(it) }
            // Updating an absent card must not delete either the saved file or the draft.
            onMain { model.save(original.copy(id = id + 1000), "Мысль", "Текст", original.size) {} }
            assertNotNull(finish().error)
            assertTrue(store.resolve(originalPath).exists())
            assertTrue(store.resolve(replacement).exists())
            onMain { model.save(original, "Мысль", "Текст", original.size) {} }
            assertNull(finish().error)
            assertFalse(store.resolve(originalPath).exists())
            val saved = db.cardDao().observeCards().first().single()
            assertEquals(replacement, saved.imagePath)
            assertTrue(store.resolve(replacement).exists())

            // A failed import keeps the saved image selected and available.
            onMain { model.beginImageEdit(saved); model.importImage(Uri.fromFile(File(app.cacheDir, "missing-source"))) }
            assertEquals(replacement, finish().draftImagePath)
            assertTrue(store.resolve(replacement).exists())
            onMain { model.removeDraftImage() }
            finish()
            assertTrue(store.resolve(replacement).exists()) // Only a draft change until Save.
            onMain { model.cancelImageEdit {} }
            finish()
            onMain { model.beginImageEdit(saved); model.delete(saved) {} }
            finish()
            assertFalse(store.resolve(replacement).exists())
            assertTrue(db.cardDao().observeCards().first().isEmpty())
        } finally {
            if (::model.isInitialized) onMain { model.viewModelScope.cancel() }
            db.close()
            for (path in paths) store.delete(path)
            source.delete()
        }
    }
}
