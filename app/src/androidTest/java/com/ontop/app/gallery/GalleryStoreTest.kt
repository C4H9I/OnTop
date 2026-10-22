package com.ontop.app.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GalleryStoreTest {
    @Test fun realMediaStorePagesAreNewestFirstWithoutDuplicatesAndThumbnailsDecode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
        val baseDate = System.currentTimeMillis() / 1000 * 1000 + 1000000
        try {
            repeat(65) { index ->
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ontop-gallery-test-${System.nanoTime()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_TAKEN, baseDate + index * 1000)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                created.add(uri)
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                resolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                    ExifInterface(descriptor.fileDescriptor).apply {
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(baseDate + index * 1000)))
                        saveAttributes()
                    }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            val store = GalleryStore(context)
            val access = GalleryAccess(true, true, false)
            val first = store.page(access)
            assertEquals(GalleryStore.PAGE_SIZE, first.size)
            assertTrue(first.zipWithNext().all { (a, b) -> a.date > b.date || a.date == b.date && a.id > b.id })
            assertEquals(created.last(), first.first().uri)
            val second = store.page(access, first.last().let { GalleryCursor(it.date, it.id) })
            assertTrue(second.isNotEmpty())
            assertTrue(first.map { it.uri }.toSet().intersect(second.map { it.uri }.toSet()).isEmpty())
            assertTrue(second.all { it.date <= first.last().date })
            val thumbnail = GalleryThumbnails(context).load(first.first())
            assertTrue(thumbnail.width > 0 && thumbnail.height > 0)
            assertTrue(store.page(GalleryAccess(false, false, false)).isEmpty())
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            created.forEach { resolver.delete(it, null, null) }
            bitmap.recycle()
        }
    }
}
