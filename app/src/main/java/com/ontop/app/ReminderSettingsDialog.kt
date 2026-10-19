package com.ontop.app

import android.Manifest
import android.app.TimePickerDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.ontop.app.reminders.*

@Composable
internal fun ReminderSettingsDialog(cardId: Long, viewModel: BoardViewModel, onDismiss: () -> Unit) {
    ReminderSettingsEditor(cardId, load = { viewModel.loadReminder(cardId)?.draft() ?: ReminderDraft() },
        persist = { viewModel.saveReminder(cardId, it.schedule, it.enabled, it.pausedUntil) }, onDismiss = onDismiss)
}

@Composable
internal fun ReminderDraftDialog(initial: ReminderDraft?, onApply: (ReminderDraft) -> Unit, onDismiss: () -> Unit) {
    ReminderSettingsEditor("note-draft", load = { initial ?: ReminderDraft() }, persist = { onApply(it) },
        onDismiss = onDismiss, draftOnly = true)
}

@Composable
private fun ReminderSettingsEditor(cardId: Any, load: suspend () -> ReminderDraft,
    persist: suspend (ReminderDraft) -> Unit, onDismiss: () -> Unit, draftOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var initialized by rememberSaveable(cardId) { mutableStateOf(false) }
    var times by rememberSaveable(cardId) { mutableStateOf("540") }
    var mode by rememberSaveable(cardId) { mutableStateOf(ScheduleMode.TIMES.name) }
    var days by rememberSaveable(cardId) { mutableIntStateOf(127) }
    var interval by rememberSaveable(cardId) { mutableIntStateOf(3) }
    var start by rememberSaveable(cardId) { mutableIntStateOf(540) }
    var end by rememberSaveable(cardId) { mutableIntStateOf(1260) }
    var quiet by rememberSaveable(cardId) { mutableStateOf(false) }
    var quietStart by rememberSaveable(cardId) { mutableIntStateOf(1320) }
    var quietEnd by rememberSaveable(cardId) { mutableIntStateOf(480) }
    var pausedUntil by rememberSaveable(cardId) { mutableStateOf<Long?>(null) }
    var enabled by rememberSaveable(cardId) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var timePicker by remember(cardId) { mutableStateOf<TimePickerDialog?>(null) }
    var datePicker by remember(cardId) { mutableStateOf<DatePickerDialog?>(null) }
    DisposableEffect(cardId) { onDispose { timePicker?.dismiss(); datePicker?.dismiss() } }
    fun pickTime(value: Int, selected: (Int) -> Unit) {
        timePicker?.dismiss()
        timePicker = TimePickerDialog(context, { _, h, m -> selected(h * 60 + m); notice = null }, value / 60, value % 60,
            android.text.format.DateFormat.is24HourFormat(context)).also { picker ->
            picker.setOnDismissListener { timePicker = null }
            picker.show()
        }
    }
    fun schedule() = ReminderSchedule(ScheduleMode.valueOf(mode), days,
        times.split(',').filter(String::isNotEmpty).map(String::toInt).toSet(), interval, start, end,
        quietStart.takeIf { quiet }, quietEnd.takeIf { quiet })
    val validation = runCatching { schedule().validate() }.exceptionOrNull()?.message
    fun canNotify() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    LaunchedEffect(cardId, retry) {
        withContext(Dispatchers.Main.immediate) {
            loading = true
            error = null
            try {
                val rule = load()
                if (!initialized) {
                    val saved = rule.schedule
                    times = saved.times.sorted().joinToString(",")
                    mode = saved.mode.name; days = saved.daysMask
                    interval = saved.intervalHours; start = saved.windowStart; end = saved.windowEnd
                    quiet = saved.quietStart != null
                    quietStart = saved.quietStart ?: 1320; quietEnd = saved.quietEnd ?: 480
                    pausedUntil = rule.pausedUntil
                    enabled = rule.enabled; initialized = true
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { error = "Не удалось загрузить напоминание. Повторите попытку." }
            loading = false
        }
    }
    fun save() {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                persist(ReminderDraft(schedule(), enabled, pausedUntil))
                if (!draftOnly && enabled && !canNotify()) {
                    notice = "Настройки сохранены. Уведомления запрещены в Android; разрешите их в настройках приложения."
                } else onDismiss()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { error = "Не удалось сохранить или запланировать напоминание. Повторите попытку." }
            finally { saving = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { save() }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Напоминания") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) Text("Загрузка…")
                if (initialized && !loading) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Напоминать о карточке", Modifier.weight(1f))
                        Switch(checked = enabled, enabled = !saving, onCheckedChange = { enabled = it; notice = null })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == "TIMES", enabled = !saving, onClick = { mode = "TIMES" }, label = { Text("По времени") })
                        FilterChip(selected = mode == "INTERVAL", enabled = !saving, onClick = { mode = "INTERVAL" }, label = { Text("По интервалу") })
                    }
                    if (mode == "TIMES") {
                        val values = schedule().times.sorted()
                        if (values.isEmpty()) Text("Добавьте хотя бы одно время.", color = MaterialTheme.colorScheme.error)
                        values.forEach { value ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = {
                                    pickTime(value) { chosen ->
                                        times = (values - value + chosen).toSortedSet().joinToString(",")
                                    }
                                }) { Text("Время: " + LocalTime.of(value / 60, value % 60)
                                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
                                TextButton(enabled = !saving, onClick = { times = (values - value).joinToString(",") }) { Text("Убрать") }
                            }
                        }
                        TextButton(enabled = !saving, onClick = {
                            pickTime(540) { times = (values + it).toSortedSet().joinToString(",") }
                        }) { Text("Добавить время") }
                    } else {
                        Text("Повторять каждые")
                        ReminderChoiceRows(ReminderSchedule.INTERVALS.map { "$it ч" }, ReminderSchedule.INTERVALS.indexOf(interval), !saving) {
                            interval = ReminderSchedule.INTERVALS[it]
                        }
                        OutlinedButton(enabled = !saving, onClick = { pickTime(start) { start = it } }) { Text("Начало: ${minuteLabel(start)}") }
                        OutlinedButton(enabled = !saving, onClick = { pickTime(end) { end = it } }) { Text("Конец: ${minuteLabel(end)}") }
                        if (start == end) Text("Начало и конец окна должны отличаться.", color = MaterialTheme.colorScheme.error)
                        Text("Отсчёт от начала окна, включая его конец. Если конец раньше начала, окно продолжается на следующий день.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Дни недели")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        weekdayLabels.forEachIndexed { index, label ->
                            val bit = 1 shl index
                            FilterChip(selected = days and bit != 0, enabled = !saving,
                                onClick = { days = days xor bit }, label = { Text(label) })
                        }
                    }
                    if (days == 0) Text("Выберите хотя бы один день недели.", color = MaterialTheme.colorScheme.error)
                    if (mode == "INTERVAL") Text("Для ночного окна выбирайте день его начала.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    FilterChip(selected = quiet, enabled = !saving, onClick = { quiet = !quiet }, label = {
                        Text(if (quiet) "Тихие часы включены" else "Добавить тихие часы")
                    })
                    if (quiet) {
                        OutlinedButton(enabled = !saving, onClick = { pickTime(quietStart) { quietStart = it } }) { Text("Тишина с ${minuteLabel(quietStart)}") }
                        OutlinedButton(enabled = !saving, onClick = { pickTime(quietEnd) { quietEnd = it } }) { Text("Тишина до ${minuteLabel(quietEnd)}") }
                        Text("В это время плановые уведомления пропускаются. Пропущенные не накапливаются.", style = MaterialTheme.typography.bodySmall)
                        if (quietStart == quietEnd) Text("Начало и конец тихих часов должны отличаться.", color = MaterialTheme.colorScheme.error)
                        else if (validation == null && nextScheduleTime(Instant.now(), ZoneId.systemDefault(), schedule()) == null) {
                            Text("Все выбранные времена попадают в тихие часы. Уведомлений не будет.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    val pauseLabel = pausedUntil?.takeIf { it > System.currentTimeMillis() }?.let {
                        "Пауза до " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    }
                    if (pauseLabel != null) {
                        Text(pauseLabel)
                        TextButton(enabled = !saving, onClick = { pausedUntil = null }) { Text("Возобновить") }
                    }
                    OutlinedButton(enabled = !saving, onClick = {
                        val initial = Instant.ofEpochMilli(pausedUntil ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
                        datePicker?.dismiss()
                        datePicker = DatePickerDialog(context, { _, y, month, day ->
                            val date = LocalDate.of(y, month + 1, day)
                            pickTime(initial.hour * 60 + initial.minute) { value ->
                                val at = date.atTime(value / 60, value % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                if (at > System.currentTimeMillis()) { pausedUntil = at; error = null }
                                else error = "Выберите дату и время в будущем."
                            }
                        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).also { picker ->
                            picker.setOnDismissListener { datePicker = null }; picker.show()
                        }
                    }) { Text(if (pauseLabel == null) "Приостановить до…" else "Изменить дату паузы") }
                    Text(if (draftOnly) "Настройки применятся при сохранении заметки. Пауза отменяет отложенное уведомление."
                        else "Пауза и возобновление применяются кнопкой «Сохранить». Пауза отменяет отложенное уведомление.", style = MaterialTheme.typography.bodySmall)
                    Text("«Отложить на 10 минут» работает и в тихие часы, не сдвигая расписание.", style = MaterialTheme.typography.bodySmall)
                    Text("По местному времени устройства. Android может доставить напоминание позже выбранного времени.")
                    if (!canNotify()) {
                        Text(if (draftOnly) "При сохранении заметки Android запросит разрешение на уведомления."
                            else "Чтобы получать важные мысли из карточек, разрешите уведомления. При сохранении включённого напоминания Android запросит разрешение.")
                        TextButton(enabled = !saving, onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text("Настройки уведомлений") }
                    }
                }
                notice?.let { Text(it) }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (!initialized) TextButton(onClick = { retry++ }) { Text("Повторить") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = initialized && !loading && !saving && validation == null, onClick = {
                if (saving) return@TextButton
                saving = true; error = null
                if (!draftOnly && enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else save()
            }) { Text(if (saving) "Сохранение…" else if (draftOnly) "Применить" else "Сохранить") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(if (notice != null) "Готово" else "Отмена") } }
    )
}
