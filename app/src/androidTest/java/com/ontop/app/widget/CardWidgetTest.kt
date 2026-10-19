package com.ontop.app.widget

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardEntity
import com.ontop.app.data.OnTopDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardWidgetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun photoIsBoundedAndReplacementIsLoaded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val store = WidgetSelectionStore(context, "widget-test-${System.nanoTime()}")
        val images = com.ontop.app.data.CardImageStore(context)
        val source = java.io.File(context.cacheDir, "widget-${System.nanoTime()}.jpg")
        val paths = mutableListOf<String>()
        try {
            val original = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
            original.eraseColor(android.graphics.Color.RED)
            source.outputStream().use { original.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            val first = images.import(android.net.Uri.fromFile(source)).also { paths.add(it) }
            val id = db.cardDao().insert(CardEntity(title = "Фото", createdAt = 1, updatedAt = 1,
                sortOrder = 0, imagePath = first))
            store.set(1, id)
            val widget = CardWidget()
            val red = widget.load(context, 1, db, store).image!!
            assertTrue(red.width <= 256 && red.height <= 256)
            assertTrue(android.graphics.Color.red(red.getPixel(0, 0)) > 200)
            red.recycle()
            original.eraseColor(android.graphics.Color.BLUE)
            source.outputStream().use { original.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            original.recycle()
            val second = images.import(android.net.Uri.fromFile(source)).also { paths.add(it) }
            db.cardDao().update(db.cardDao().findById(id)!!.copy(imagePath = second))
            val blue = widget.load(context, 1, db, store).image!!
            assertTrue(android.graphics.Color.blue(blue.getPixel(0, 0)) > 200)
            blue.recycle()
            val document = com.ontop.app.text.RichDocument("", media = listOf(
                com.ontop.app.text.NoteMedia("inline", 0, com.ontop.app.text.MediaKind.IMAGE, path = first)))
            val card = db.cardDao().findById(id)!!.copy(formatting = com.ontop.app.text.RichTextCodec.encode(document))
            db.cardDao().update(card)
            assertEquals(second, card.widgetCoverPath())
            db.cardDao().update(card.copy(imagePath = null))
            val fallback = widget.load(context, 1, db, store).image!!
            assertTrue(android.graphics.Color.red(fallback.getPixel(0, 0)) > 200)
            fallback.recycle()
            store.save(1, WidgetSelection(WidgetMode.ROTATION, currentId = id))
            val rotation = widget.load(context, 1, db, store)
            assertEquals(WidgetMode.ROTATION, rotation.mode)
            assertTrue(android.graphics.Color.red(rotation.image!!.getPixel(0, 0)) > 200)
            rotation.image.recycle()
        } finally {
            db.close(); store.remove(1); paths.forEach { images.delete(it) }; source.delete()
        }
    }

    @Test fun selectionsPersistAndRemainIndependent() = runBlocking {
        val name = "widget-test-${System.nanoTime()}"
        val store = WidgetSelectionStore(context, name)
        try {
            assertNull(store.get(1))
            store.set(1, 100)
            store.set(2, 200)
            store.set(3, 100)
            val reopened = WidgetSelectionStore(context, name)
            assertEquals(100L, reopened.get(1))
            assertArrayEquals(intArrayOf(1, 3), reopened.idsFor(100).sortedArray())
            reopened.set(1, 200)
            reopened.remove(2)
            assertNull(store.get(2))
            assertEquals(200L, store.get(1))
            assertEquals(100L, store.get(3))
        } finally { store.remove(1); store.remove(2); store.remove(3) }
    }

    @Test fun widgetLoadsEditedCardMissingImageDeletedCardAndDatabaseFailure() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val store = WidgetSelectionStore(context, "widget-test-${System.nanoTime()}")
        val widget = CardWidget()
        try {
            val id = db.cardDao().insert(CardEntity(title = "Фраза", description = "Не в виджете",
                createdAt = 1, updatedAt = 1, sortOrder = 0))
            store.set(1, id)
            val original = db.cardDao().findById(id)!!
            assertEquals("Фраза", widget.load(context, 1, db, store).title)
            db.cardDao().update(original.copy(title = "Новая фраза", imagePath = "images/0000.jpg"))
            val changed = widget.load(context, 1, db, store)
            assertEquals("Новая фраза", changed.title)
            assertTrue(changed.unavailableImage)
            assertNull(changed.image)
            assertEquals(id, changed.cardId)
            db.cardDao().delete(original)
            val deleted = widget.load(context, 1, db, store)
            assertEquals("Карточка удалена", deleted.title)
            assertNull(deleted.cardId)
            assertEquals(id, store.get(1))
            val unavailable = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        throw android.database.sqlite.SQLiteException("Simulated unavailable database")
                    }
                }).build()
            try {
                assertEquals("Не удалось загрузить карточку", widget.load(context, 1, unavailable, store).title)
            } finally { unavailable.close() }
        } finally { db.close(); store.remove(1) }
    }
}
