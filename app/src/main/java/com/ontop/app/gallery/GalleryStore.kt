package com.ontop.app.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class GalleryItem(val uri: Uri, val id: Long, val date: Long,
    val video: Boolean, val duration: Long = 0, val name: String = "")
internal data class GalleryCursor(val date: Long, val id: Long)
internal enum class GalleryFilter { ALL, IMAGES, VIDEOS }
internal enum class MediaDestination { CONTENT, COVER, VIDEO }

/** Keyset pages across both collections; no file paths or unrestricted Files collection queries. */
internal class GalleryStore(private val context: Context) {
    companion object { const val PAGE_SIZE = 60 }

    suspend fun page(access: GalleryAccess, before: GalleryCursor? = null,
        filter: GalleryFilter = GalleryFilter.ALL): List<GalleryItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<GalleryItem>()
        for (video in listOf(false, true)) {
            if (video && filter == GalleryFilter.IMAGES || !video && filter == GalleryFilter.VIDEOS) continue
            if (if (video) !access.canQueryVideos else !access.canQueryImages) continue
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME) +
                if (video) arrayOf(MediaStore.Video.Media.DURATION) else emptyArray()
            // DATE_TAKEN is milliseconds, DATE_ADDED is seconds. Unknown capture dates use import time.
            val date = "CASE WHEN datetaken > 0 THEN datetaken ELSE date_added * 1000 END"
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$date DESC, _id DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
                if (before != null) {
                    // CASE has no SQLite column affinity; cast bound strings for numeric comparison.
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "($date < CAST(? AS INTEGER) OR ($date = CAST(? AS INTEGER) AND _id < ?))")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(before.date.toString(), before.date.toString(), before.id.toString()))
                }
            }
            context.contentResolver.query(collection, projection, args, null)?.use { cursor ->
                var count = 0
                while (count < PAGE_SIZE && cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(0)
                    val taken = cursor.getLong(1)
                    result.add(GalleryItem(ContentUris.withAppendedId(collection, id), id,
                        if (taken > 0) taken else cursor.getLong(2) * 1000,
                        video, if (video) cursor.getLong(4) else 0, cursor.getString(3).orEmpty()))
                    count++
                }
            }
        }
        result.sortedWith(compareByDescending<GalleryItem> { it.date }.thenByDescending { it.id }).take(PAGE_SIZE)
    }
}

/** Shared per open sheet: bounded memory and at most three provider thumbnail requests at once. */
internal class GalleryThumbnails(private val context: Context) {
    private val workers = Semaphore(3)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun clear() { cache.evictAll() }
    @Suppress("DEPRECATION")
    suspend fun load(item: GalleryItem): Bitmap = workers.withPermit { withContext(Dispatchers.IO) {
        val key = item.uri.toString()
        cache.get(key)?.let { return@withContext it }
        currentCoroutineContext().ensureActive()
        val bitmap = if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
        else if (item.video) MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, item.id,
            MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, item.id,
            MediaStore.Images.Thumbnails.MINI_KIND, null)
        checkNotNull(bitmap) { "Thumbnail unavailable" }
        currentCoroutineContext().ensureActive()
        cache.put(key, bitmap)
        bitmap
    } }
}
