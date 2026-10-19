package com.ontop.app.reminders

import com.ontop.app.data.ReminderEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun ReminderEntity.schedule() = ReminderSchedule(
    mode = ScheduleMode.valueOf(mode), daysMask = daysMask,
    times = if (times.isEmpty()) setOf(hour * 60 + minute) else times.split(',').map(String::toInt).toSet(),
    intervalHours = intervalHours, windowStart = windowStart, windowEnd = windowEnd,
    quietStart = quietStart, quietEnd = quietEnd
)

internal fun minuteLabel(minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
internal val weekdayLabels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

internal fun ReminderEntity.summary(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (!enabled) return "Напоминание выключено"
    val schedule = schedule()
    val days = weekdayLabels.filterIndexed { index, _ -> daysMask and (1 shl index) != 0 }.joinToString(", ")
    val base = if (schedule.mode == ScheduleMode.TIMES) {
        if (daysMask == 127 && schedule.times.size == 1) "Ежедневно в ${minuteLabel(schedule.times.single())}"
        else "$days · ${schedule.times.sorted().joinToString(", ", transform = ::minuteLabel)}"
    } else "Каждые $intervalHours ч · ${minuteLabel(windowStart)}–${minuteLabel(windowEnd)}" +
        if (daysMask == 127) "" else " · $days"
    val quiet = quietStart?.let { " · тихие часы ${minuteLabel(it)}–${minuteLabel(quietEnd!!)}" }.orEmpty()
    val pause = pausedUntil?.takeIf { it > now.toEpochMilli() }?.let {
        "Пауза до " + Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " · "
    }.orEmpty()
    return pause + base + quiet
}
