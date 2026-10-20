package com.ontop.app

import kotlin.math.atan2

internal data class NotePercentages(val importance: Int, val progress: Int?) : java.io.Serializable

internal fun parsePercentage(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..100 }

/** Twelve 25-degree segments separated by five-degree gaps; the final segment can be partial. */
internal fun importanceSweeps(value: Int): List<Float> = List(12) { index ->
    (value.coerceIn(0, 100) * 12f / 100f - index).coerceIn(0f, 1f) * 25f
}

internal fun clockwiseDegrees(x: Float, y: Float): Float =
    ((Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 450.0) % 360.0).toFloat()

/** The shortest movement across 12 o'clock, without wrapping 100 back to zero. */
internal fun movedPercentage(value: Float, previousAngle: Float, angle: Float): Float =
    (value + ((angle - previousAngle + 540f) % 360f - 180f) / 3.6f).coerceIn(0f, 100f)
