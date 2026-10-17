package com.ontop.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/** Paths are relative to filesDir, so images can travel with the database in a future export. */
class CardImageStore(private val context: Context) {
    fun resolve(path: String): File {
        require(Regex("images/[a-f0-9-]+\\.jpg").matches(path)) { "Invalid image path" }
        return File(context.filesDir, path)
    }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val path = "images/${UUID.randomUUID()}.jpg"
        val file = resolve(path)
        var bitmap: Bitmap? = null
        var oriented: Bitmap? = null
        var output: Bitmap? = null
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image" }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: throw IOException("Cannot decode image")
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                try {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (error: IOException) {
                    // Some platform versions do not expose EXIF for otherwise decodable formats.
                    if (bounds.outMimeType == "image/jpeg") throw error
                    ExifInterface.ORIENTATION_NORMAL
                }
            } ?: throw IOException("Cannot read orientation")
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val decoded = bitmap!!
            val factor = minOf(1f, 1600f / max(decoded.width, decoded.height))
            matrix.postScale(factor, factor)
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            // Flatten transparency to white before JPEG encoding; no original EXIF/GPS is copied.
            output = Bitmap.createBitmap(oriented!!.width, oriented!!.height, Bitmap.Config.ARGB_8888)
            Canvas(output!!).apply { drawColor(Color.WHITE); drawBitmap(oriented!!, 0f, 0f, null) }
            file.outputStream().use {
                if (!output!!.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("Cannot encode image")
            }
            path
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally {
            output?.recycle()
            if (oriented !== bitmap) oriented?.recycle()
            bitmap?.recycle()
        }
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path != null) {
            val file = resolve(path)
            if (file.exists() && !file.delete()) throw IOException("Cannot remove image")
        }
    }

    suspend fun load(path: String, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val file = resolve(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Image missing or damaged")
    }
}
