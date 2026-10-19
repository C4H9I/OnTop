package com.ontop.app.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun TextColorPalette(document: RichDocument, start: Int, end: Int, enabled: Boolean,
    onColor: (String?) -> Unit, onClose: () -> Unit) {
    val theme = MaterialTheme.colorScheme
    val dark = theme.background.luminance() < 0.5f
    val current = document.selectedTextColor(start, end)
    val canApply = enabled && start != end
    val previewColor = current.value?.let { richColor(it, dark) } ?: theme.onSurface
    Column(Modifier.width(280.dp).padding(12.dp).testTag("text-color-palette"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Цвет текста", style = MaterialTheme.typography.titleSmall)
        if (start == end) Text("Выделите текст, чтобы изменить его цвет", style = MaterialTheme.typography.bodySmall)
        textColorPalette.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { choice ->
                    val chosen = !current.mixed && choice.value.equals(current.value, ignoreCase = true)
                    val color = choice.value?.let { richColor(it, dark) } ?: theme.onSurface
                    Row(Modifier.weight(1f).heightIn(min = 56.dp).focusProperties { canFocus = false }
                        .clickable(enabled = canApply, role = Role.RadioButton) { onColor(choice.value) }
                        .semantics { contentDescription = choice.name; selected = chosen }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(40.dp).border(if (chosen) 2.dp else 1.dp,
                            if (chosen) theme.primary else theme.outline, CircleShape).padding(4.dp)
                            .background(color, CircleShape), contentAlignment = Alignment.Center) {
                            if (chosen) Text("✓", color = if (color.luminance() > 0.4f) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { })
                        }
                        Text(choice.name, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!current.mixed && current.name == "Другой цвет") {
                Box(Modifier.size(24.dp).border(1.dp, theme.outline, CircleShape).background(previewColor, CircleShape))
            }
            Text(current.name + (if (current.name == "Другой цвет" && current.value?.startsWith("#") == true) " · ${current.value}" else ""),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("text-color-name"))
        }
        // Keep the exact assigned colour. The neutral theme surface and outline make the
        // sample distinct in both themes without silently substituting a different colour.
        Surface(color = theme.surface, shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.outline)) {
            Text("Важная мысль", color = previewColor, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("text-color-preview"))
        }
        TextButton(onClick = { onColor(null) }, enabled = canApply,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusProperties { canFocus = false }) {
            Text("Обычный цвет")
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .focusProperties { canFocus = false }) { Text("Готово") }
    }
}
