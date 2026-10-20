package com.ontop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
internal fun NotePercentagesDialog(noteId: Long, importance: Int, progress: Int?,
    onDismiss: () -> Unit, onSave: (Int, Int?) -> Unit, saving: Boolean = false, error: String? = null,
    automaticProgress: Boolean = false) {
    var importanceText by rememberSaveable(noteId) { mutableStateOf(importance.toString()) }
    var progressText by rememberSaveable(noteId) { mutableStateOf((progress ?: 0).toString()) }
    var lastImportance by rememberSaveable(noteId) { mutableIntStateOf(importance) }
    var lastProgress by rememberSaveable(noteId) { mutableIntStateOf(progress ?: 0) }
    var tracking by rememberSaveable(noteId) { mutableStateOf(progress != null) }
    val parsedImportance = parsePercentage(importanceText)
    val parsedProgress = parsePercentage(progressText)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Важность и готовность") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Важность", style = MaterialTheme.typography.titleMedium)
            PercentageControl("importance", importanceText, lastImportance, !saving, MaterialTheme.colorScheme.primary,
                onText = { text -> importanceText = text; parsePercentage(text)?.let { lastImportance = it } },
                onValue = { lastImportance = it; importanceText = it.toString() })
            if (!automaticProgress) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Отслеживать готовность", Modifier.weight(1f))
                Switch(tracking, { tracking = it }, enabled = !saving, modifier = Modifier.testTag("progress-tracking"))
            }
            Text("Готовность", style = MaterialTheme.typography.titleMedium)
            if (automaticProgress) Text("Рассчитывается по выполненным пунктам")
            else PercentageControl("progress", progressText, lastProgress, !saving && tracking, Color(0xFF438CBA),
                onText = { text -> progressText = text; parsePercentage(text)?.let { lastProgress = it } },
                onValue = { lastProgress = it; progressText = it.toString() })
            if (!tracking && !automaticProgress) Text("Готовность для этой заметки не отслеживается.", style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !saving && parsedImportance != null && (!tracking || parsedProgress != null),
            modifier = Modifier.testTag("percent-save"), onClick = { onSave(parsedImportance!!, if (tracking) parsedProgress else null) }) {
            Text(if (saving) "Сохранение…" else "Сохранить")
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.testTag("percent-cancel")) { Text("Отмена") }
    })
}

@Composable
private fun PercentageControl(key: String, text: String, value: Int, enabled: Boolean, color: Color,
    onText: (String) -> Unit, onValue: (Int) -> Unit) {
    val invalid = parsePercentage(text) == null
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PercentageDial(value, enabled, color, onValue, Modifier.size(96.dp).testTag("$key-dial").semantics {
                contentDescription = if (key == "importance") "Круговой регулятор важности" else "Круговой регулятор готовности"
            })
            OutlinedTextField(text, { onText(it.take(32)) }, enabled = enabled, singleLine = true,
                label = { Text("Процент") }, suffix = { Text("%") }, isError = enabled && invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("$key-input").semantics {
                    contentDescription = if (key == "importance") "Важность, точный процент" else "Готовность, точный процент"
                })
        }
        Slider(value.toFloat(), { onValue(it.roundToInt().coerceIn(0, 100)) }, enabled = enabled,
            valueRange = 0f..100f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
            modifier = Modifier.fillMaxWidth().testTag("$key-slider"))
        if (enabled && invalid) Text("Введите целое число от 0 до 100", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Touch positions use the same 12 o'clock origin as the reminder clock; a full turn clamps at 100. */
@Composable
private fun PercentageDial(value: Int, enabled: Boolean, color: Color, onValue: (Int) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValue)
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    Box(modifier.semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f)
        if (enabled) setProgress { change(it.roundToInt().coerceIn(0, 100)); true } else disabled()
    }.pointerInput(enabled) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown()
            val center = Offset(size.width / 2f, size.height / 2f)
            var previous = clockwiseDegrees(down.position.x - center.x, down.position.y - center.y)
            var accumulated = if (previous < 1.8f && current > 50) 100f else previous / 3.6f
            val validTouch = (down.position - center).getDistance() >= size.width * .2f
            if (validTouch) { down.consume(); change(accumulated.roundToInt()) }
            do {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (validTouch && pointer.pressed) {
                    val angle = clockwiseDegrees(pointer.position.x - center.x, pointer.position.y - center.y)
                    accumulated = movedPercentage(accumulated, previous, angle)
                    previous = angle
                    pointer.consume(); change(accumulated.roundToInt())
                }
            } while (event.changes.any { it.pressed })
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 5.dp.toPx()
            val radius = size.minDimension / 2 - 9.dp.toPx()
            val center = Offset(size.width / 2, size.height / 2)
            val origin = center - Offset(radius, radius)
            val circleSize = Size(radius * 2, radius * 2)
            drawCircle(inactive, radius, center, style = Stroke(width))
            if (enabled) {
                drawArc(color, -90f, value * 3.6f, false, origin, circleSize, style = Stroke(width))
                val radians = Math.toRadians(value * 3.6 - 90)
                drawCircle(color, 7.dp.toPx(), center + Offset(cos(radians).toFloat() * radius, sin(radians).toFloat() * radius))
            }
        }
        Text(if (enabled) "$value%" else "—", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics { })
    }
}
