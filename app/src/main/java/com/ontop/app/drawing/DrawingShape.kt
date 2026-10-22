package com.ontop.app.drawing

import android.graphics.*
import java.io.Serializable
import kotlin.math.*

enum class ShapeType(val label: String) { LINE("Линия"), ARROW("Стрелка"), RECTANGLE("Прямоугольник"), ELLIPSE("Эллипс") }
/** Endpoints for lines; normalized opposite bounds for rectangles and ellipses. */
data class DrawingShape(val id: String, val type: ShapeType, val x1: Float, val y1: Float,
    val x2: Float, val y2: Float, val color: Int = Color.BLACK, val width: Float = 4f,
    val order: Int = 0) : Serializable {
    fun normalized() = if (type == ShapeType.LINE || type == ShapeType.ARROW) this
        else copy(x1 = min(x1, x2), y1 = min(y1, y2), x2 = max(x1, x2), y2 = max(y1, y2))
    fun valid() = if (type == ShapeType.LINE || type == ShapeType.ARROW) hypot(x2-x1, y2-y1) >= 2f
        else abs(x2-x1) >= 2f && abs(y2-y1) >= 2f
}

internal object ShapeGeometry {
    fun bounds(s: DrawingShape) = RectF(min(s.x1,s.x2), min(s.y1,s.y2), max(s.x1,s.x2), max(s.y1,s.y2))
    fun handles(s: DrawingShape): List<DrawingPoint> = if (s.type == ShapeType.LINE || s.type == ShapeType.ARROW)
        listOf(DrawingPoint(s.x1,s.y1), DrawingPoint(s.x2,s.y2))
        else listOf(DrawingPoint(s.x1,s.y1), DrawingPoint(s.x2,s.y1), DrawingPoint(s.x2,s.y2), DrawingPoint(s.x1,s.y2))
    private fun arrow(s: DrawingShape): List<DrawingPoint> {
        val length = hypot(s.x2-s.x1,s.y2-s.y1)
        if (length == 0f) return emptyList()
        val head = min(length * .6f, max(12f, s.width * 4f))
        val ux = (s.x2-s.x1)/length; val uy = (s.y2-s.y1)/length
        return listOf(-1f,1f).map { DrawingPoint(s.x2-ux*head-uy*head*.5f*it, s.y2-uy*head+ux*head*.5f*it) }
    }
    fun draw(canvas: Canvas, s: DrawingShape) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=s.color; strokeWidth=s.width; style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        when(s.type) {
            ShapeType.LINE, ShapeType.ARROW -> {
                canvas.drawLine(s.x1,s.y1,s.x2,s.y2,p)
                if(s.type == ShapeType.ARROW) arrow(s).forEach { canvas.drawLine(s.x2,s.y2,it.x,it.y,p) }
            }
            ShapeType.RECTANGLE -> canvas.drawRect(bounds(s),p)
            ShapeType.ELLIPSE -> canvas.drawOval(bounds(s),p)
        }
    }
    private fun segment(x: Float,y: Float,a: DrawingPoint,b: DrawingPoint): Float {
        val dx=b.x-a.x; val dy=b.y-a.y
        val t=if(dx*dx+dy*dy == 0f) 0f else (((x-a.x)*dx+(y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f)
        return hypot(x-a.x-t*dx,y-a.y-t*dy)
    }
    fun hit(s: DrawingShape,x: Float,y: Float,padding: Float): Boolean {
        val tolerance=padding+s.width/2
        val h=handles(s)
        return when(s.type) {
            ShapeType.LINE,ShapeType.ARROW -> segment(x,y,h[0],h[1]) <= tolerance ||
                (s.type == ShapeType.ARROW && arrow(s).any { segment(x,y,h[1],it) <= tolerance })
            ShapeType.RECTANGLE -> h.indices.any { segment(x,y,h[it],h[(it+1)%4]) <= tolerance }
            ShapeType.ELLIPSE -> {
                val b=bounds(s); val rx=b.width()/2; val ry=b.height()/2
                rx > 0 && ry > 0 && abs(hypot((x-b.centerX())/rx,(y-b.centerY())/ry)-1f)*min(rx,ry) <= tolerance
            }
        }
    }
    fun drag(s: DrawingShape, handle: Int?, start: DrawingPoint, end: DrawingPoint, doc: DrawingDocument): DrawingShape {
        if(handle == null) {
            val b=bounds(s)
            val dx=(end.x-start.x).coerceIn(-b.left,doc.width-b.right)
            val dy=(end.y-start.y).coerceIn(-b.top,doc.height-b.bottom)
            return s.copy(x1=s.x1+dx,y1=s.y1+dy,x2=s.x2+dx,y2=s.y2+dy)
        }
        val x=end.x.coerceIn(0f,doc.width.toFloat()); val y=end.y.coerceIn(0f,doc.height.toFloat())
        return if(s.type == ShapeType.LINE || s.type == ShapeType.ARROW) {
            if(handle == 0) s.copy(x1=x,y1=y) else s.copy(x2=x,y2=y)
        } else when(handle) {
            0 -> s.copy(x1=x,y1=y)
            1 -> s.copy(x2=x,y1=y)
            2 -> s.copy(x2=x,y2=y)
            else -> s.copy(x1=x,y2=y)
        }
    }
}
