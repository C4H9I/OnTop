package com.ontop.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CardImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrationPreservesVersionTwoCardsAndCommentsAndImagePathSurvivesReopen() = runBlocking {
        val name = "migration-images-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        try {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val schema = assets.open("com.ontop.app.data.OnTopDatabase/2.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("database")
            }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (i in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO cards VALUES (7, 'Мысль', 'Текст', 'WIDE', 100, 200, 3)")
                old.execSQL("INSERT INTO card_comments VALUES (9, 7, 'История', 300)")
                old.version = 2
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name)
                .addMigrations(OnTopDatabase.MIGRATION_2_3, OnTopDatabase.MIGRATION_3_4, OnTopDatabase.MIGRATION_4_5, OnTopDatabase.MIGRATION_5_6).build()
            val card = db.cardDao().observeCards().first()
            assertEquals(CardEntity(7, "Мысль", "Текст", CardSize.WIDE, 100, 200, 3), card.single())
            assertEquals(CardCommentEntity(9, 7, "История", 300),
                db.cardCommentDao().observeComments(7).first().single())
            db.cardDao().update(card.single().copy(imagePath = "images/test.jpg"))
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals("images/test.jpg", db.cardDao().observeCards().first().single().imagePath)
            assertEquals(1, db.cardCommentDao().observeComments(7).first().size)
        } finally { db?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun importResizesRotatesCopiesAndDeletesPrivateImage() = runBlocking {
        val source = File(context.cacheDir, "source-${System.nanoTime()}.jpg")
        val store = CardImageStore(context)
        var path: String? = null
        try {
            val bitmap = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            path = store.import(Uri.fromFile(source))
            assertTrue(path.startsWith("images/"))
            assertFalse(File(path).isAbsolute)
            assertTrue(store.resolve(path).isFile)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(store.resolve(path).path, bounds)
            assertEquals(800, bounds.outWidth)
            assertEquals(1600, bounds.outHeight)
            source.delete() // Private copy remains usable without the original URI.
            val loaded = store.load(path, 600)
            assertTrue(maxOf(loaded.width, loaded.height) <= 600)
            loaded.recycle()
            store.delete(path)
            assertFalse(store.resolve(path).exists())
        } finally { store.delete(path); source.delete() }
    }

    @Test
    fun allExifOrientationsIncludingMirrorsAreApplied() = runBlocking {
        val source = File(context.cacheDir, "orientation-${System.nanoTime()}.jpg")
        val store = CardImageStore(context)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2)
        )
        try {
            for (orientation in 1..8) {
                val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                for (i in 0..3) {
                    val left = (i % 2) * 60f
                    val top = (i / 2) * 40f
                    canvas.drawRect(left, top, left + 60, top + 40, Paint().apply { color = colors[i] })
                }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bitmap.recycle()
                ExifInterface(source.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                }
                val path = store.import(Uri.fromFile(source))
                try {
                    val result = store.load(path, 1600)
                    try {
                        assertEquals(if (orientation >= 5) 80 else 120, result.width)
                        for (i in 0..3) {
                            val actual = result.getPixel(result.width * (if (i % 2 == 0) 1 else 3) / 4,
                                result.height * (if (i / 2 == 0) 1 else 3) / 4)
                            val wanted = colors[expected[orientation - 1][i]]
                            assertTrue("EXIF $orientation corner $i", listOf(
                                kotlin.math.abs(Color.red(actual) - Color.red(wanted)),
                                kotlin.math.abs(Color.green(actual) - Color.green(wanted)),
                                kotlin.math.abs(Color.blue(actual) - Color.blue(wanted))).all { it < 40 })
                        }
                    } finally { result.recycle() }
                } finally { store.delete(path) }
            }
        } finally { source.delete() }
    }

    @Test
    fun transparentPngIsFlattenedOnWhiteWithoutUpscaling() = runBlocking {
        val source = File(context.cacheDir, "transparent-${System.nanoTime()}.png")
        val store = CardImageStore(context)
        var path: String? = null
        try {
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            path = store.import(Uri.fromFile(source))
            val result = store.load(path, 1600)
            try {
                assertEquals(80, result.width)
                assertEquals(40, result.height)
                assertEquals(Color.WHITE, result.getPixel(10, 10))
            } finally { result.recycle() }
        } finally { store.delete(path); source.delete() }
    }

    @Test
    fun corruptAndMissingSourcesDoNotLeaveFiles() = runBlocking {
        val store = CardImageStore(context)
        val source = File(context.cacheDir, "broken-${System.nanoTime()}.jpg")
        val directory = File(context.filesDir, "images")
        val before = directory.list()?.toSet().orEmpty()
        try {
            source.writeText("not an image")
            for (uri in listOf(Uri.fromFile(source), Uri.fromFile(File(context.cacheDir, "missing-image")))) {
                try { store.import(uri); fail("Import must fail") }
                catch (_: IOException) { }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(before, directory.list()?.toSet().orEmpty())
            try { store.resolve("../outside.jpg"); fail("Invalid path must be rejected") }
            catch (_: IllegalArgumentException) { }
        } finally { source.delete() }
    }
}
