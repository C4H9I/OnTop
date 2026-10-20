package com.ontop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardEntity
import com.ontop.app.ui.theme.*

private val cardColors = listOf(Sage, Sand, Color(0xFFDFE5D7), Terracotta, Color(0xFFECE2D4), Forest)

internal fun cardBackground(id: Long): Color = cardColors[Math.floorMod(id - 1, cardColors.size.toLong()).toInt()]

/** Shared by the board and details: text grows freely, while part of the photo remains visible. */
@Composable
internal fun CardVisual(
    card: CardEntity,
    modifier: Modifier = Modifier,
    minHeight: Dp = 280.dp,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    maxImageSide: Int = 600
) {
    val background = cardBackground(card.id)
    val hasImage = card.imagePath != null
    Box(modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(20.dp))
        .background(background), contentAlignment = Alignment.BottomStart) {
        card.imagePath?.let { path ->
            LocalCardImage(path, Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                maxSide = maxImageSide, showError = false)
        }
        val titleSurface = if (hasImage) Modifier.drawWithCache {
            val gradient = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                endY = 48.dp.toPx())
            onDrawBehind { drawRect(gradient) }
        }.padding(top = 48.dp) else Modifier
        Text(card.title, style = titleStyle,
            color = if (hasImage || background == Forest) Color.White else Ink,
            modifier = Modifier.fillMaxWidth().padding(top = if (hasImage) 64.dp else 0.dp)
                .then(titleSurface).padding(16.dp))
    }
}
