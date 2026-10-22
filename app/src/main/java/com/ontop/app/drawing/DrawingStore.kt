package com.ontop.app.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ontop.app.data.CardImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.File
import java.util.UUID

object DrawingStore {
    fun isDrawing(path: String) = Regex("images/[a-f0-9-]+\\.png").matches(path)
    fun modelPath(path: String): String { require(isDrawing(path)); return path.removeSuffix(".png") + ".drawing.json" }
    fun sourcePath(path: String): String { require(isDrawing(path)); return path.removeSuffix(".png") + ".source" }
    // Includes an optional source candidate for cleanup/journals; old drawings need only PNG + JSON.
    fun resources(path: String): List<String> = if (isDrawing(path)) listOf(path, modelPath(path), sourcePath(path)) else listOf(path)
    fun validateResources(path: String, resolve: (String) -> File): DrawingDocument {
        val model = resolve(modelPath(path)); require(model.isFile && model.length() in 1..8_000_000)
        val document = DrawingCodec.decode(model.readText(Charsets.UTF_8))
        val source = resolve(sourcePath(path))
        if (document.backgroundImage == null) require(!source.exists())
        else {
            require(document.backgroundImage == sourcePath(path) && source.isFile && source.length() <= 16L * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth == document.width && bounds.outHeight == document.height)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
        }
        return document
    }
    suspend fun open(context: Context, path: String): DrawingDocument = withContext(Dispatchers.IO) {
        val images = CardImageStore(context)
        if (isDrawing(path) && images.resolve(modelPath(path)).isFile) load(context, path)
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(images.resolve(path).path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            DrawingDocument(bounds.outWidth, bounds.outHeight, backgroundImage = path).also { DrawingCodec.decode(DrawingCodec.encode(it)) }
        }
    }
    suspend fun background(context: Context, document: DrawingDocument, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        document.backgroundImage?.let { path ->
            // Existing local imports already apply EXIF and retain oriented dimensions.
            val images = CardImageStore(context)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(images.resolve(path).path, bounds)
            require(bounds.outWidth == document.width && bounds.outHeight == document.height)
            images.load(path, maxSide)
        }
    }
    suspend fun load(context: Context, path: String): DrawingDocument = withContext(Dispatchers.IO) {
        validateResources(path, CardImageStore(context)::resolve)
    }
    suspend fun save(context: Context, document: DrawingDocument): String = withContext(Dispatchers.IO) {
        val json = DrawingCodec.encode(document)
        require(json.toByteArray(Charsets.UTF_8).size <= 8_000_000)
        DrawingCodec.decode(json) // Validate before creating any resources.
        val path = "images/${UUID.randomUUID()}.png"
        val images = CardImageStore(context)
        val png = images.resolve(path); val model = images.resolve(modelPath(path))
        val source = images.resolve(sourcePath(path))
        check(png.parentFile!!.isDirectory || png.parentFile!!.mkdirs())
        try {
            document.backgroundImage?.let { original ->
                val input = images.resolve(original)
                require(input.isFile && input.length() <= 16L * 1024 * 1024)
                FileOutputStream(source).use { output -> input.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            }
            val saved = document.copy(backgroundImage = if (document.backgroundImage == null) null else sourcePath(path))
            FileOutputStream(model).use { it.write(DrawingCodec.encode(saved).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val background = background(context, document, 2048)
            val bitmap = try { DrawingRenderer.bitmap(document, background) } finally { background?.recycle() }
            try { FileOutputStream(png).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } }
            finally { bitmap.recycle() }
            path // A fresh UUID is published only after both resources are complete.
        } catch (error: Throwable) { png.delete(); model.delete(); source.delete(); throw error }
    }
}
