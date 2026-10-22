package com.ontop.app.drawing

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class DrawingPoint(val x: Float, val y: Float) : Serializable
data class DrawingOperation(val type: String, val points: List<DrawingPoint> = emptyList(),
    val color: Int = Color.BLACK, val width: Float = 4f) : Serializable
data class DrawingText(val id: String, val text: String, val x: Float, val y: Float,
    val fontSize: Float = 32f, val color: Int = Color.BLACK, val order: Int = 0) : Serializable

data class DrawingDocument(val width: Int = 600, val height: Int = 800,
    val background: Int = Color.WHITE, val operations: List<DrawingOperation> = emptyList(),
    val backgroundImage: String? = null, val texts: List<DrawingText> = emptyList(), val shapes: List<DrawingShape> = emptyList()) : Serializable

object DrawingCodec {
    const val MAX_POINTS = 100_000
    fun encode(document: DrawingDocument): String = JSONObject().put("version", 4)
        .put("width", document.width).put("height", document.height).put("background", document.background)
        .put("backgroundImage", document.backgroundImage ?: JSONObject.NULL)
        .put("operations", JSONArray().apply { document.operations.forEach { operation ->
            put(JSONObject().put("type", operation.type).put("color", operation.color).put("width", operation.width)
                .put("points", JSONArray().apply { operation.points.forEach { put(JSONArray().put(it.x).put(it.y)) } }))
        } }).put("texts", JSONArray().apply { document.texts.forEach {
            put(JSONObject().put("id", it.id).put("text", it.text).put("x", it.x).put("y", it.y)
                .put("fontSize", it.fontSize).put("color", it.color).put("order", it.order))
        } }).put("shapes", JSONArray().apply { document.shapes.forEach {
            put(JSONObject().put("id", it.id).put("type", it.type.name).put("x1", it.x1).put("y1", it.y1)
                .put("x2", it.x2).put("y2", it.y2).put("color", it.color).put("width", it.width).put("order", it.order))
        } }).toString()
    fun decode(value: String): DrawingDocument {
        val json = JSONObject(value)
        val version = json.getInt("version"); require(version in 1..4)
        val canvasWidth = json.getInt("width"); val canvasHeight = json.getInt("height")
        val source = if (version >= 2 && !json.isNull("backgroundImage")) json.getString("backgroundImage") else null
        if (source == null) require(canvasWidth == 600 && canvasHeight == 800)
        else {
            require(Regex("images/[a-f0-9-]+\\.(jpg|png|source)").matches(source))
            require(canvasWidth in 1..30000 && canvasHeight in 1..30000 && canvasWidth.toLong() * canvasHeight <= 100_000_000)
        }
        require(json.getInt("background") == Color.WHITE)
        val operations = json.getJSONArray("operations")
        require(operations.length() <= 10_000)
        var total = 0
        val result = (0 until operations.length()).map { index ->
            val operation = operations.getJSONObject(index)
            val type = operation.getString("type"); require(type in setOf("DRAW", "ERASE", "CLEAR"))
            val width = operation.getDouble("width").toFloat(); require(width.isFinite() && width in 1f..24f)
            val points = operation.getJSONArray("points"); total += points.length(); require(total <= MAX_POINTS)
            val parsed = (0 until points.length()).map { i ->
                val point = points.getJSONArray(i)
                val x = point.getDouble(0).toFloat(); val y = point.getDouble(1).toFloat()
                require(x.isFinite() && y.isFinite() && x in 0f..canvasWidth.toFloat() && y in 0f..canvasHeight.toFloat())
                DrawingPoint(x, y)
            }
            require(type == "CLEAR" || parsed.isNotEmpty())
            DrawingOperation(type, parsed, operation.getInt("color"), width)
        }
        val texts = if (version < 3) emptyList() else {
            val array = json.getJSONArray("texts"); require(array.length() <= 1000)
            (0 until array.length()).map { index ->
                val t = array.getJSONObject(index)
                DrawingText(t.getString("id"), t.getString("text"), t.getDouble("x").toFloat(),
                    t.getDouble("y").toFloat(), t.getDouble("fontSize").toFloat(), t.getInt("color"), t.getInt("order")).also {
                    require(it.id.length in 1..100 && it.text.isNotBlank() && it.text.length <= 10000)
                    require(it.x.isFinite() && it.y.isFinite() && it.x in 0f..canvasWidth.toFloat() && it.y in 0f..canvasHeight.toFloat())
                    require(it.fontSize.isFinite() && it.fontSize in 8f..200f && it.order >= 0)
                }
            }.also { require(it.map { t -> t.id }.distinct().size == it.size) }
        }
        val shapes = if (version < 4) emptyList() else {
            val array = json.getJSONArray("shapes"); require(array.length() <= 1000)
            (0 until array.length()).map { index ->
                val t = array.getJSONObject(index)
                DrawingShape(t.getString("id"), ShapeType.valueOf(t.getString("type")),
                    t.getDouble("x1").toFloat(), t.getDouble("y1").toFloat(),
                    t.getDouble("x2").toFloat(), t.getDouble("y2").toFloat(),
                    t.getInt("color"), t.getDouble("width").toFloat(), t.getInt("order")).also {
                    require(it.id.length in 1..100 && it.order >= 0)
                    require(listOf(it.x1, it.x2).all { v -> v.isFinite() && v in 0f..canvasWidth.toFloat() })
                    require(listOf(it.y1, it.y2).all { v -> v.isFinite() && v in 0f..canvasHeight.toFloat() })
                    require(it.width.isFinite() && it.width in 1f..24f && it.valid())
                    require(it.type == ShapeType.LINE || it.type == ShapeType.ARROW || (it.x1 <= it.x2 && it.y1 <= it.y2))
                }
            }.also { require(it.map { s -> s.id }.distinct().size == it.size) }
        }
        return DrawingDocument(canvasWidth, canvasHeight, operations = result, backgroundImage = source, texts = texts, shapes = shapes)
    }
}

/** Shared renderer for the preview and PNG. CLEAR affects only the transparent stroke layer. */
object DrawingRenderer {
    fun draw(canvas: Canvas, document: DrawingDocument, preview: DrawingOperation? = null, backgroundImage: Bitmap? = null) {
        val bounds = canvas.save()
        canvas.clipRect(0f, 0f, document.width.toFloat(), document.height.toFloat())
        canvas.drawColor(document.background)
        backgroundImage?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, document.width.toFloat(), document.height.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        val layer = canvas.saveLayer(0f, 0f, document.width.toFloat(), document.height.toFloat(), null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        (document.operations.asSequence() + listOfNotNull(preview).asSequence()).forEach { operation ->
            if (operation.type == "CLEAR") canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            else if (operation.points.isNotEmpty()) {
                paint.color = operation.color; paint.strokeWidth = operation.width
                paint.xfermode = if (operation.type == "ERASE") PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
                val first = operation.points.first()
                if (operation.points.size == 1) {
                    paint.style = Paint.Style.FILL; canvas.drawCircle(first.x, first.y, operation.width / 2, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    val path = Path().apply {
                        moveTo(first.x, first.y)
                        for (index in 1 until operation.points.size) {
                            val a = operation.points[index - 1]; val b = operation.points[index]
                            quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
                        }
                        val last = operation.points.last(); lineTo(last.x, last.y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restoreToCount(layer)
        document.texts.sortedBy { it.order }.forEach { text ->
            val paint = textPaint(text)
            text.text.split('\n').forEachIndexed { index, line ->
                canvas.drawText(line, text.x, text.y - paint.fontMetrics.ascent + index * paint.fontSpacing, paint)
            }
        }
        document.shapes.sortedBy { it.order }.forEach { ShapeGeometry.draw(canvas, it) }
        canvas.restoreToCount(bounds)
    }
    private fun textPaint(text: DrawingText) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text.color; textSize = text.fontSize; typeface = Typeface.DEFAULT
    }
    fun textBounds(text: DrawingText): RectF {
        val paint = textPaint(text)
        val lines = text.text.split('\n')
        return RectF(text.x, text.y, text.x + (lines.maxOfOrNull { paint.measureText(it) } ?: 0f).coerceAtLeast(12f),
            text.y + paint.fontSpacing * lines.size)
    }
    fun position(text: DrawingText, document: DrawingDocument): DrawingText {
        val bounds = textBounds(text)
        return text.copy(x = text.x.coerceIn(0f, (document.width - minOf(bounds.width(), 24f)).coerceAtLeast(0f)),
            y = text.y.coerceIn(0f, (document.height - minOf(bounds.height(), 24f)).coerceAtLeast(0f)))
    }
    fun bitmap(document: DrawingDocument, backgroundImage: Bitmap? = null): Bitmap {
        require(document.backgroundImage == null || backgroundImage != null)
        val scale = if (document.backgroundImage == null) 2f else minOf(1f, 2048f / maxOf(document.width, document.height))
        val width = (document.width * scale).toInt().coerceAtLeast(1)
        val height = (document.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it); canvas.scale(width.toFloat() / document.width, height.toFloat() / document.height)
            draw(canvas, document, backgroundImage = backgroundImage)
        }
    }
    fun hasInk(document: DrawingDocument): Boolean {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.scale(300f / document.width, 400f / document.height)
            draw(canvas, document.copy(background = Color.TRANSPARENT))
            val pixels = IntArray(300 * 400); bitmap.getPixels(pixels, 0, 300, 0, 0, 300, 400)
            return pixels.any { Color.alpha(it) != 0 }
        } finally { bitmap.recycle() }
    }
}
