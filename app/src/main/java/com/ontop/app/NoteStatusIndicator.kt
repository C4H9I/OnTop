package com.ontop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class StatusIndicatorSize { Compact, Expanded }

@Composable
internal fun NoteStatusIndicator(importance: Int, progress: Int?, modifier: Modifier = Modifier,
    variant: StatusIndicatorSize = StatusIndicatorSize.Expanded,
    diameter: Dp = if (variant == StatusIndicatorSize.Compact) 48.dp else 112.dp,
    enabled: Boolean = true, onClick: (() -> Unit)? = null, showBackground: Boolean = true) {
    val green = Color(0xFF39D353)
    val blue = Color(0xFF00C8FF)
    val background = Color(0xFF202820).copy(alpha = .8f)
    val inactive = if (showBackground) Color(0xFFB9BEB9).copy(alpha = .35f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    val values = "Важность: $importance%. Готовность: ${progress?.let { "$it%" } ?: "не отслеживается"}"
    Box(modifier.size(diameter).clip(CircleShape).then(if (onClick != null) Modifier.clickable(enabled = enabled,
        role = Role.Button, onClickLabel = "Изменить важность и готовность", onClick = onClick) else Modifier)
        .semantics(mergeDescendants = true) { contentDescription = values }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension - 4.dp.toPx()
            val outer = side * .064f
            val inner = side * if (variant == StatusIndicatorSize.Compact) .04f else .031f
            val center = Offset(size.width / 2, size.height / 2)
            if (showBackground) drawCircle(background, radius = size.minDimension / 2, center = center)
            val outerRadius = side / 2 - outer
            val outerOrigin = center - Offset(outerRadius, outerRadius)
            val outerSize = Size(outerRadius * 2, outerRadius * 2)
            importanceSweeps(importance).forEachIndexed { index, sweep ->
                drawArc(inactive, -90f + index * 30f, 25f, false, outerOrigin, outerSize, style = Stroke(outer))
                if (sweep > 0) drawArc(green, -90f + index * 30f, sweep, false,
                    outerOrigin, outerSize, style = Stroke(outer))
            }
            val innerRadius = side * .33f
            drawCircle(inactive, innerRadius, center, style = Stroke(inner))
            if (progress != null && progress > 0) drawArc(blue, -90f, progress.coerceIn(0, 100) * 3.6f,
                false, center - Offset(innerRadius, innerRadius), Size(innerRadius * 2, innerRadius * 2),
                style = Stroke(inner, cap = StrokeCap.Butt))
        }
        if (variant == StatusIndicatorSize.Expanded) Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clearAndSetSemantics { }) {
            Text("$importance%", style = MaterialTheme.typography.titleLarge,
                color = if (showBackground) Color.White else MaterialTheme.colorScheme.onSurface)
            Text(progress?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelMedium, color = blue)
        }
    }
}

