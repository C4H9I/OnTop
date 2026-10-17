package com.ontop.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.Forest
import com.ontop.app.ui.theme.Ink

@Composable
internal fun CardSizeSelector(size: CardSize, enabled: Boolean, onSelect: (CardSize) -> Unit) {
    Column(Modifier.testTag("card-size-selector"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Размер карточки", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (option in CardSize.entries) {
                val selected = size == option
                val shape = RoundedCornerShape(16.dp)
                val illustration = if (selected) Forest else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(shape)
                        .background(if (selected) Color(0xFFDCE5D4) else MaterialTheme.colorScheme.surface)
                        .border(BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) Forest else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)), shape)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { onSelect(option) })
                        .padding(horizontal = 4.dp, vertical = 8.dp).testTag("size-${option.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterEnd) {
                        if (selected) Icon(painterResource(R.drawable.ic_check), null,
                            tint = Forest, modifier = Modifier.size(20.dp).testTag("size-check-${option.name}"))
                    }
                    Canvas(Modifier.fillMaxWidth().height(56.dp).testTag("size-preview-${option.name}")) {
                        val previewHeight = if (option == CardSize.NORMAL) 48.dp.toPx() else 28.dp.toPx()
                        val previewWidth = if (option == CardSize.NORMAL) previewHeight * 0.75f
                            else minOf(this.size.width * 0.9f, previewHeight * 2f)
                        val left = (this.size.width - previewWidth) / 2
                        val top = (this.size.height - previewHeight) / 2
                        drawRoundRect(illustration.copy(alpha = 0.2f), Offset(left, top),
                            Size(previewWidth, previewHeight), CornerRadius(6.dp.toPx()))
                        for (line in 0..1) {
                            drawRoundRect(illustration, Offset(left + 6.dp.toPx(), top + previewHeight - (12 - line * 5).dp.toPx()),
                                Size(previewWidth * if (line == 0) 0.65f else 0.45f, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                        }
                    }
                    Text(if (option == CardSize.NORMAL) "Обычная" else "Широкая",
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        color = if (selected) Ink else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().testTag("size-label-${option.name}"))
                }
            }
        }
        Text(if (size == CardSize.NORMAL) "Занимает одну колонку доски." else "Занимает всю ширину доски.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("size-explanation"))
    }
}
