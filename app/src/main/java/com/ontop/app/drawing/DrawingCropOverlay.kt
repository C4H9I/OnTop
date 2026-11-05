package com.ontop.app.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

@Composable
internal fun DrawingCropOverlay(frame: CropFrame, viewport: DrawingViewportState, ratio: Float?,
    onChange: (CropFrame) -> Unit) {
    val currentFrame by rememberUpdatedState(frame)
    val currentRatio by rememberUpdatedState(ratio)
    val currentChange by rememberUpdatedState(onChange)
    val stepX = (viewport.value.documentWidth * .02f).coerceAtLeast(1f)
    val stepY = (viewport.value.documentHeight * .02f).coerceAtLeast(1f)
    Canvas(Modifier.fillMaxSize().testTag("drawing-crop-overlay").semantics {
        contentDescription = "Область обрезки: ${frame.width.toInt()} на ${frame.height.toInt()}"
        customActions = listOf(
            CustomAccessibilityAction("Сдвинуть область влево") {
                onChange(DrawingCrop.drag(frame, CropHandle.MOVE, -stepX, 0f,
                    viewport.value.documentWidth, viewport.value.documentHeight, ratio)); true
            },
            CustomAccessibilityAction("Сдвинуть область вправо") {
                onChange(DrawingCrop.drag(frame, CropHandle.MOVE, stepX, 0f,
                    viewport.value.documentWidth, viewport.value.documentHeight, ratio)); true
            },
            CustomAccessibilityAction("Сдвинуть область вверх") {
                onChange(DrawingCrop.drag(frame, CropHandle.MOVE, 0f, -stepY,
                    viewport.value.documentWidth, viewport.value.documentHeight, ratio)); true
            },
            CustomAccessibilityAction("Сдвинуть область вниз") {
                onChange(DrawingCrop.drag(frame, CropHandle.MOVE, 0f, stepY,
                    viewport.value.documentWidth, viewport.value.documentHeight, ratio)); true
            },
            CustomAccessibilityAction("Уменьшить ширину") {
                onChange(DrawingCrop.drag(frame, CropHandle.RIGHT, -stepX, 0f,
                    viewport.value.documentWidth, viewport.value.documentHeight, ratio)); true
            },
            CustomAccessibilityAction("Уменьшить высоту") {
                onChange(DrawingCrop.drag(frame, CropHandle.BOTTOM, 0f, -stepY,
                    viewport.value.documentWidth, viewport.value.documentHeight, ratio)); true
            }
        )
    }.pointerInput(viewport) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val transform = viewport.value
            val startFrame = currentFrame
            val startPoint = transform.documentPoint(down.position)
            val positions = listOf(
                CropHandle.TOP_LEFT to Offset(startFrame.left, startFrame.top),
                CropHandle.TOP to Offset((startFrame.left + startFrame.right) / 2, startFrame.top),
                CropHandle.TOP_RIGHT to Offset(startFrame.right, startFrame.top),
                CropHandle.RIGHT to Offset(startFrame.right, (startFrame.top + startFrame.bottom) / 2),
                CropHandle.BOTTOM_RIGHT to Offset(startFrame.right, startFrame.bottom),
                CropHandle.BOTTOM to Offset((startFrame.left + startFrame.right) / 2, startFrame.bottom),
                CropHandle.BOTTOM_LEFT to Offset(startFrame.left, startFrame.bottom),
                CropHandle.LEFT to Offset(startFrame.left, (startFrame.top + startFrame.bottom) / 2))
            val radius = 24.dp.toPx()
            val handle = positions.minByOrNull { (_, point) ->
                val screen = transform.screenPoint(point)
                hypot(down.position.x - screen.x, down.position.y - screen.y)
            }?.takeIf { (_, point) ->
                val screen = transform.screenPoint(point)
                hypot(down.position.x - screen.x, down.position.y - screen.y) <= radius
            }?.first ?: if (startPoint.x in startFrame.left..startFrame.right &&
                startPoint.y in startFrame.top..startFrame.bottom) CropHandle.MOVE else null
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change != null && handle != null) {
                    val point = viewport.value.documentPoint(change.position)
                    currentChange(DrawingCrop.drag(startFrame, handle, point.x - startPoint.x,
                        point.y - startPoint.y, viewport.value.documentWidth, viewport.value.documentHeight, currentRatio))
                }
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }) {
        val transform = viewport.value
        val first = transform.screenPoint(Offset(frame.left, frame.top))
        val last = transform.screenPoint(Offset(frame.right, frame.bottom))
        val left = first.x.coerceIn(0f, size.width); val top = first.y.coerceIn(0f, size.height)
        val right = last.x.coerceIn(0f, size.width); val bottom = last.y.coerceIn(0f, size.height)
        val shade = Color.Black.copy(alpha = .43f)
        drawRect(shade, size = Size(size.width, top))
        drawRect(shade, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(shade, topLeft = Offset(0f, top), size = Size(left, bottom - top))
        drawRect(shade, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
        val points = listOf(Offset(left, top), Offset((left + right) / 2, top), Offset(right, top),
            Offset(right, (top + bottom) / 2), Offset(right, bottom), Offset((left + right) / 2, bottom),
            Offset(left, bottom), Offset(left, (top + bottom) / 2))
        points.forEach { point ->
            val side = 12.dp.toPx()
            drawRect(Color.White, Offset(point.x - side / 2, point.y - side / 2), Size(side, side))
            drawRect(Color(0xFF304734), Offset(point.x - side / 2, point.y - side / 2),
                Size(side, side), style = Stroke(1.dp.toPx()))
        }
    }
}
