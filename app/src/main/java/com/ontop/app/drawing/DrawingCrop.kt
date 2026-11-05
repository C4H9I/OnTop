package com.ontop.app.drawing

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class CropFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    fun pixels(document: DrawingDocument): SourceCrop {
        val l = floor(left).toInt().coerceIn(0, document.width - 1)
        val t = floor(top).toInt().coerceIn(0, document.height - 1)
        return SourceCrop(l, t, ceil(right).toInt().coerceIn(l + 1, document.width),
            ceil(bottom).toInt().coerceIn(t + 1, document.height))
    }

    fun aspect(ratio: Float): CropFrame {
        val w = min(width, height * ratio)
        val h = w / ratio
        val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
        return CropFrame(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}

internal enum class CropHandle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

internal object DrawingCrop {
    fun initial(document: DrawingDocument): CropFrame {
        val x = document.width * .08f; val y = document.height * .08f
        return CropFrame(x, y, document.width - x, document.height - y)
    }

    fun drag(start: CropFrame, handle: CropHandle, dx: Float, dy: Float, width: Int, height: Int,
        ratio: Float?): CropFrame {
        val minW = min(8f, width.toFloat()); val minH = min(8f, height.toFloat())
        if (handle == CropHandle.MOVE) {
            val x = dx.coerceIn(-start.left, width - start.right)
            val y = dy.coerceIn(-start.top, height - start.bottom)
            return CropFrame(start.left + x, start.top + y, start.right + x, start.bottom + y)
        }
        var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
        if (handle in setOf(CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT)) l = (l + dx).coerceIn(0f, r - minW)
        if (handle in setOf(CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT)) r = (r + dx).coerceIn(l + minW, width.toFloat())
        if (handle in setOf(CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT)) t = (t + dy).coerceIn(0f, b - minH)
        if (handle in setOf(CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT)) b = (b + dy).coerceIn(t + minH, height.toFloat())
        if (ratio == null) return CropFrame(l, t, r, b)
        val horizontal = handle == CropHandle.LEFT || handle == CropHandle.RIGHT
        val vertical = handle == CropHandle.TOP || handle == CropHandle.BOTTOM
        if (horizontal) {
            val cy = (start.top + start.bottom) / 2f
            val maxH = 2f * min(cy, height - cy)
            val w = min(r - l, maxH * ratio).coerceAtLeast(minW)
            val h = w / ratio
            if (handle == CropHandle.LEFT) l = r - w else r = l + w
            t = cy - h / 2f; b = cy + h / 2f
        } else if (vertical) {
            val cx = (start.left + start.right) / 2f
            val maxW = 2f * min(cx, width - cx)
            val h = min(b - t, maxW / ratio).coerceAtLeast(minH)
            val w = h * ratio
            if (handle == CropHandle.TOP) t = b - h else b = t + h
            l = cx - w / 2f; r = cx + w / 2f
        } else {
            val leftHandle = handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT
            val topHandle = handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT
            val anchorX = if (leftHandle) start.right else start.left
            val anchorY = if (topHandle) start.bottom else start.top
            val maxW = if (leftHandle) anchorX else width - anchorX
            val maxH = if (topHandle) anchorY else height - anchorY
            val desired = if (abs(dx) >= abs(dy) * ratio) r - l else (b - t) * ratio
            val w = min(desired, min(maxW, maxH * ratio)).coerceAtLeast(minW)
            val h = w / ratio
            l = if (leftHandle) anchorX - w else anchorX; r = l + w
            t = if (topHandle) anchorY - h else anchorY; b = t + h
        }
        return CropFrame(l.coerceAtLeast(0f), t.coerceAtLeast(0f), r.coerceAtMost(width.toFloat()), b.coerceAtMost(height.toFloat()))
    }

    fun apply(document: DrawingDocument, frame: CropFrame): DrawingDocument {
        val local = frame.pixels(document)
        require(local.width > 0 && local.height > 0)
        val source = document.sourceCrop ?: SourceCrop(0, 0, document.width, document.height)
        val sourceCrop = if (document.backgroundImage == null) null else SourceCrop(
            source.left + local.left, source.top + local.top, source.left + local.right, source.top + local.bottom)
        val bounds = RectF(local.left.toFloat(), local.top.toFloat(), local.right.toFloat(), local.bottom.toFloat())
        return document.copy(width = local.width, height = local.height, sourceCrop = sourceCrop,
            operations = document.operations.map { operation -> operation.copy(points = operation.points.map {
                DrawingPoint(it.x - local.left, it.y - local.top)
            }) },
            texts = document.texts.filter { RectF.intersects(DrawingRenderer.textBounds(it), bounds) }
                .map { it.copy(x = it.x - local.left, y = it.y - local.top) },
            shapes = document.shapes.filter {
                val padding = max(1f, it.width / 2f)
                RectF.intersects(ShapeGeometry.bounds(it).apply { inset(-padding, -padding) }, bounds)
            }
                .map { it.copy(x1 = it.x1 - local.left, y1 = it.y1 - local.top,
                    x2 = it.x2 - local.left, y2 = it.y2 - local.top) })
    }
}
