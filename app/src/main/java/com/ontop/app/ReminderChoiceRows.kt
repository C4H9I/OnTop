package com.ontop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun ReminderChoiceRows(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, enabled = enabled, onClick = { onSelect(index) }, label = { Text(label) })
        }
    }
}
