package com.ontop.app.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One gesture owns the canvas until all fingers are released. Navigation cancels its draft edit. */
@Composable
internal fun DrawingCanvas(session: DrawingSession, viewport: DrawingViewportState, eraser: Boolean,
    textTool: Boolean, color: Int, width: Float, selectedId: String?, onSelect: (String?) -> Unit,
    onEdit: (DrawingText) -> Unit, modifier: Modifier = Modifier, shapeTool: ShapeType? = null,
    selectedShapeId: String? = null, onSelectShape: (String?) -> Unit = {}) {
    var preview by remember { mutableStateOf<DrawingOperation?>(null) }
    var draftShape by remember { mutableStateOf<DrawingShape?>(null) }
    val currentShapeSelect by rememberUpdatedState(onSelectShape)
    val currentShapeId by rememberUpdatedState(selectedShapeId)
    var movingText by remember { mutableStateOf<DrawingText?>(null) }
    val currentColor by rememberUpdatedState(color)
    val currentWidth by rememberUpdatedState(width)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentEdit by rememberUpdatedState(onEdit)
    val currentSelected by rememberUpdatedState(selectedId)
    BoxWithConstraints(modifier.background(Color(0xFFF4F6F3)).clipToBounds().testTag("drawing-viewport")
        .onSizeChanged { viewport.resize(Size(it.width.toFloat(), it.height.toFloat())) }
        .pointerInput(viewport, eraser, textTool, shapeTool, session.busy) {
            if (!session.busy) awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                val start = viewport.value.documentPoint(down.position)
                val inside = viewport.value.contains(start)
                val hitPadding = 8f / viewport.value.scale
                val hit = if (textTool && inside) session.document.texts.sortedByDescending { it.order }.firstOrNull {
                    DrawingRenderer.textBounds(it).apply { inset(-hitPadding, -hitPadding) }.contains(start.x, start.y)
                } else null
                val touchRadius = 24.dp.toPx() / viewport.value.scale
                val selectedShape = session.document.shapes.firstOrNull { it.id == currentShapeId }
                val handle = if (shapeTool != null) selectedShape?.let { shape ->
                    ShapeGeometry.handles(shape).indices.minByOrNull { i ->
                        val p = ShapeGeometry.handles(shape)[i]; kotlin.math.hypot(start.x-p.x,start.y-p.y)
                    }?.takeIf { i -> val p = ShapeGeometry.handles(shape)[i]; kotlin.math.hypot(start.x-p.x,start.y-p.y) <= touchRadius }
                } else null
                val shapeHit = if (shapeTool != null && inside) {
                    if (handle != null) selectedShape else session.document.shapes.sortedByDescending { it.order }.firstOrNull {
                        ShapeGeometry.hit(it,start.x,start.y,12.dp.toPx()/viewport.value.scale)
                    }
                } else null
                if (shapeTool != null && inside) currentShapeSelect(shapeHit?.id)
                val newShape = if (shapeTool != null && inside && shapeHit == null) DrawingShape(
                    java.util.UUID.randomUUID().toString(), shapeTool, start.x,start.y,start.x,start.y,currentColor,currentWidth,
                    (session.document.shapes.maxOfOrNull { it.order } ?: -1)+1) else null
                draftShape = newShape
                val wasSelected = currentSelected == hit?.id
                if (textTool && inside) currentSelect(hit?.id)
                fun point(position: Offset): DrawingPoint {
                    val p = viewport.value.documentPoint(position)
                    return DrawingPoint(p.x.coerceIn(0f, session.document.width.toFloat()),
                        p.y.coerceIn(0f, session.document.height.toFloat()))
                }
                val points = mutableStateListOf<DrawingPoint>()
                if (inside && !textTool && shapeTool == null) {
                    points.add(point(down.position))
                    preview = DrawingOperation(if (eraser) "ERASE" else "DRAW", points, currentColor, currentWidth)
                }
                var navigation = false
                var moved = false
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) navigation = true
                        if (navigation) {
                            preview = null; movingText = null; draftShape = null
                            // Only pointers present in both samples contribute: adding/removing a finger cannot jump the centroid.
                            val common = event.changes.filter { it.pressed && it.previousPressed }
                            if (common.size >= 2) {
                                val previous = common.fold(Offset.Zero) { sum, it -> sum + it.previousPosition } / common.size.toFloat()
                                val center = common.fold(Offset.Zero) { sum, it -> sum + it.position } / common.size.toFloat()
                                val oldSpan = common.sumOf { (it.previousPosition - previous).getDistance().toDouble() }.toFloat()
                                val newSpan = common.sumOf { (it.position - center).getDistance().toDouble() }.toFloat()
                                viewport.navigate(if (oldSpan > 0f) newSpan / oldSpan else 1f, previous, center)
                            }
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null) {
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (inside && shapeTool != null) {
                                    val end = point(change.position)
                                    draftShape = if (shapeHit != null) {
                                        if (moved) ShapeGeometry.drag(shapeHit,handle,DrawingPoint(start.x,start.y),end,session.document) else null
                                    } else newShape?.copy(x2=end.x,y2=end.y)
                                } else if (inside && textTool && hit != null && moved) {
                                    val current = viewport.value.documentPoint(change.position)
                                    movingText = DrawingRenderer.position(hit.copy(x = hit.x + current.x - start.x,
                                        y = hit.y + current.y - start.y), session.document)
                                } else if (inside && !textTool && shapeTool == null) {
                                    val next = point(change.position)
                                    if (points.size < 10_000 && points.lastOrNull() != next) points.add(next)
                                }
                                if (!change.pressed) released = true
                            }
                        }
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (!navigation && inside && released) {
                        if (shapeTool != null) draftShape?.takeIf { it.valid() && (shapeHit != null || moved) }?.let { session.shape(it); currentShapeSelect(it.id) }
                        else if (!textTool) preview?.let { session.add(it.copy(points = points.toList())) }
                        else if (moved && hit != null) movingText?.let(session::text)
                        else if (!moved) {
                            if (hit == null) currentEdit(DrawingText(java.util.UUID.randomUUID().toString(), "", start.x, start.y,
                                color = color, order = (session.document.texts.maxOfOrNull { it.order } ?: -1) + 1))
                            else if (wasSelected) currentEdit(hit)
                        }
                    }
                } finally { preview = null; movingText = null; draftShape = null }
            }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val transform = viewport.value
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val saved = native.save()
                native.translate(transform.origin.x, transform.origin.y); native.scale(transform.scale, transform.scale)
                val textDocument = movingText?.let { moving -> session.document.copy(texts = session.document.texts.map { if (it.id == moving.id) moving else it }) } ?: session.document
                val rendered = draftShape?.let { shape -> textDocument.copy(shapes = textDocument.shapes.filterNot { it.id == shape.id } + shape.normalized()) } ?: textDocument
                DrawingRenderer.draw(native, rendered, preview, session.backgroundImage)
                if (textTool) rendered.texts.firstOrNull { it.id == selectedId }?.let {
                    native.clipRect(0f, 0f, rendered.width.toFloat(), rendered.height.toFloat())
                    native.drawRect(DrawingRenderer.textBounds(it).apply { inset(-3f, -3f) }, android.graphics.Paint().apply {
                        this.color = android.graphics.Color.rgb(0, 120, 200); style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.5f / transform.scale
                    })
                }
                if (shapeTool != null) rendered.shapes.firstOrNull { it.id == selectedShapeId }?.let { shape ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = android.graphics.Color.rgb(0,120,200); style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f/transform.scale
                    }
                    native.drawRect(ShapeGeometry.bounds(shape),paint)
                    ShapeGeometry.handles(shape).forEach { p ->
                        paint.style = android.graphics.Paint.Style.FILL; paint.color = android.graphics.Color.WHITE
                        native.drawCircle(p.x,p.y,7.dp.toPx()/transform.scale,paint)
                        paint.style = android.graphics.Paint.Style.STROKE; paint.color = android.graphics.Color.rgb(0,120,200)
                        native.drawCircle(p.x,p.y,7.dp.toPx()/transform.scale,paint)
                    }
                }
                native.restoreToCount(saved)
            }
        }
        // Retain the fitted document bounds as a stable accessibility/test landmark, including letterboxing.
        val ratio = session.document.width.toFloat() / session.document.height
        Box(Modifier.width(minOf(maxWidth, maxHeight * ratio)).aspectRatio(ratio).testTag("drawing-canvas"))
    }
}
