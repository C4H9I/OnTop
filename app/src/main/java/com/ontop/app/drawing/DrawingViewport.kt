package com.ontop.app.drawing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** View-only transform. Neither this state nor navigation gestures enter the drawing document. */
internal data class DrawingViewport(val documentWidth: Int, val documentHeight: Int,
    val size: Size = Size.Zero, val zoom: Float = 1f, val pan: Offset = Offset.Zero) {
    val fitScale get() = if (size.width > 0 && size.height > 0)
        minOf(size.width / documentWidth, size.height / documentHeight) else 1f
    val scale get() = fitScale * zoom
    val origin get() = Offset((size.width - documentWidth * scale) / 2,
        (size.height - documentHeight * scale) / 2) + pan
    fun documentPoint(screen: Offset) = (screen - origin) / scale
    fun screenPoint(document: Offset) = origin + document * scale
    fun contains(point: Offset) = point.x in 0f..documentWidth.toFloat() && point.y in 0f..documentHeight.toFloat()
    private fun bounded(): DrawingViewport {
        val limitX = ((documentWidth * scale - size.width) / 2).coerceAtLeast(0f)
        val limitY = ((documentHeight * scale - size.height) / 2).coerceAtLeast(0f)
        return copy(pan = Offset(if (limitX == 0f) 0f else pan.x.coerceIn(-limitX, limitX),
            if (limitY == 0f) 0f else pan.y.coerceIn(-limitY, limitY)))
    }
    fun navigate(factor: Float, previousCenter: Offset, center: Offset): DrawingViewport {
        if (!factor.isFinite() || factor <= 0f) return this
        val anchor = documentPoint(previousCenter)
        val next = copy(zoom = (zoom * factor).coerceIn(1f, 5f), pan = Offset.Zero)
        return next.copy(pan = center - anchor * next.scale - next.origin).bounded()
    }
    fun resized(next: Size): DrawingViewport {
        val resized = copy(size = next)
        return resized.copy(pan = pan * (resized.fitScale / fitScale)).bounded()
    }
}

internal class DrawingViewportState(width: Int, height: Int) {
    var value by mutableStateOf(DrawingViewport(width, height)); private set
    fun resize(size: Size) { value = value.resized(size) }
    fun navigate(factor: Float, previous: Offset, center: Offset) { value = value.navigate(factor, previous, center) }
    fun fit() { value = value.copy(zoom = 1f, pan = Offset.Zero) }
}
