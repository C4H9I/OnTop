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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import com.ontop.app.data.CardEntity
import com.ontop.app.ui.theme.*

private val cardColors = listOf(Sage, Sand, Color(0xFFDFE5D7), Terracotta, Color(0xFFECE2D4), Forest)

/** Shorten only words wider than the tile's actual text area. The database text stays intact. */
internal fun tileDisplayTitle(title: String, widthPx: Int, style: TextStyle, measurer: TextMeasurer): String {
    if (widthPx <= 0) return title
    return Regex("\\S+|\\s+").findAll(title).joinToString("") { match ->
        val word = match.value
        if (word.isBlank() || measurer.measure(word, style = style, softWrap = false).size.width <= widthPx) word
        else {
            val points = word.codePointCount(0, word.length)
            var low = 0
            var high = points
            while (low < high) {
                val middle = (low + high + 1) / 2
                val candidate = word.substring(0, word.offsetByCodePoints(0, middle)) + "…"
                if (measurer.measure(candidate, style = style, softWrap = false).size.width <= widthPx) low = middle
                else high = middle - 1
            }
            word.substring(0, word.offsetByCodePoints(0, low)) + "…"
        }
    }
}

internal fun cardBackground(id: Long): Color = cardColors[Math.floorMod(id - 1, cardColors.size.toLong()).toInt()]

/** Shared by the board and details: text grows freely, while part of the photo remains visible. */
@Composable
internal fun CardVisual(
    card: CardEntity,
    modifier: Modifier = Modifier,
    minHeight: Dp = 280.dp,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    maxImageSide: Int = 600,
    maxTitleLines: Int = Int.MAX_VALUE
) {
    val background = cardBackground(card.id)
    val hasImage = card.imagePath != null
    val titleTextStyle = titleStyle.copy(lineBreak = LineBreak.Simple, hyphens = Hyphens.None)
    val textMeasurer = rememberTextMeasurer()
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
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = if (hasImage) 64.dp else 0.dp)
            .then(titleSurface).padding(16.dp)) {
            val displayTitle = remember(card.title, constraints.maxWidth, titleTextStyle, maxTitleLines) {
                if (maxTitleLines == Int.MAX_VALUE) card.title
                else tileDisplayTitle(card.title, constraints.maxWidth, titleTextStyle, textMeasurer)
            }
            Text(displayTitle, style = titleTextStyle,
                color = if (hasImage || background == Forest) Color.White else Ink,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = card.title },
                maxLines = maxTitleLines, overflow = TextOverflow.Ellipsis)
        }
    }
}
