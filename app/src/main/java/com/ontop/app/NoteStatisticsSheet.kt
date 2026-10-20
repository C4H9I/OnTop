package com.ontop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import com.ontop.app.data.NoteInteractionRepository
import com.ontop.app.data.NoteInteractionStatistics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

internal data class NoteStatisticsState(
    val statistics: NoteInteractionStatistics? = null, val failed: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteStatisticsSheet(noteId: Long, updatedAt: Long,
    repository: NoteInteractionRepository, lifecycle: Lifecycle, onDismiss: () -> Unit) {
    var retry by remember(noteId) { mutableIntStateOf(0) }
    val state by produceState(NoteStatisticsState(), noteId, repository, lifecycle, retry) {
        value = NoteStatisticsState()
        try {
            repository.observeStatistics(noteId).flowWithLifecycle(lifecycle).collect {
                value = NoteStatisticsState(statistics = it)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("NoteStatistics", "Cannot read local interaction statistics", error)
            value = NoteStatisticsState(failed = true)
        }
    }
    MaterialTheme(colorScheme = noteReadingColors()) {
        ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface) {
            NoteStatisticsContent(state, updatedAt, onRetry = { retry++ })
        }
    }
}

@Composable
internal fun NoteStatisticsContent(state: NoteStatisticsState, updatedAt: Long,
    modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
    fun date(value: Long?) = value?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Нет данных"
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("note-statistics"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Статистика внимания", style = MaterialTheme.typography.headlineSmall)
        when {
            state.failed -> {
                Text("Не удалось загрузить статистику")
                TextButton(onClick = onRetry) { Text("Повторить") }
            }
            state.statistics == null -> {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("Загрузка статистики…")
            }
            else -> {
                val statistics = state.statistics
                if (statistics.events == 0L) Text("История взаимодействий пока отсутствует")
                StatisticsValue("Открытия заметки", statistics.opened.toString(), "opened")
                StatisticsValue("Просмотры в слайдере", statistics.slider.toString(), "slider")
                StatisticsValue("Открытия из виджета", statistics.widget.toString(), "widget")
                StatisticsValue("Добавленные комментарии", statistics.comments.toString(), "comments")
                StatisticsValue("Сохранённые изменения", statistics.edited.toString(), "edited")
                HorizontalDivider()
                StatisticsValue("Всего осознанных обращений", statistics.intentionalVisits.toString(), "visits")
                StatisticsValue("Последнее обращение", date(statistics.lastOpenedAt), "last-visit")
                StatisticsValue("Последнее изменение", date(updatedAt), "last-edit")
                Text("Учитываются только события, зарегистрированные с этапа 15.1. " +
                    "Нулевые значения не означают отсутствия интереса к заметке. " +
                    "Добавленные комментарии — число зарегистрированных добавлений, включая впоследствии удалённые комментарии.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatisticsValue(label: String, value: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("statistics-$tag"))
    }
}
