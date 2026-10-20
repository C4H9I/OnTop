package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardEntity
import com.ontop.app.data.TagEntity

@Composable
internal fun noteReadingColors() = MaterialTheme.colorScheme.copy(background = Color.White,
    surface = Color.White, onBackground = Color(0xFF202820), onSurface = Color(0xFF202820),
    onSurfaceVariant = Color(0xFF566056))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteStatusWithTopics(card: CardEntity, tags: List<TagEntity>, tag: String,
    enabled: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, diameter: Dp = 112.dp) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NoteStatusIndicator(card.importance, card.progress, Modifier.testTag(tag), diameter = diameter,
            enabled = enabled, onClick = onClick, showBackground = false)
        if (tags.isNotEmpty()) FlowRow(Modifier.weight(1f).heightIn(max = 192.dp)
            .verticalScroll(rememberScrollState()).testTag("$tag-topics"),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { topic -> Surface(shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)) {
                Text(topic.name, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            } }
        }
    }
}

@Composable
internal fun NoteReadingTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp))
}
