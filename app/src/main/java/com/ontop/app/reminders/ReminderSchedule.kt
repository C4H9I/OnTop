package com.ontop.app.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class ScheduleMode { TIMES, INTERVAL }

/** Minutes from local midnight; Monday is bit 0. An overnight window belongs to its start day. */
data class ReminderSchedule(
    val mode: ScheduleMode = ScheduleMode.TIMES,
    val daysMask: Int = 127,
    val times: Set<Int> = setOf(540),
    val intervalHours: Int = 3,
    val windowStart: Int = 540,
    val windowEnd: Int = 1260,
    val quietStart: Int? = null,
    val quietEnd: Int? = null
) : java.io.Serializable {
    fun validate() {
        require(daysMask in 1..127) { "Выберите хотя бы один день недели." }
        require(times.all { it in 0..1439 }) { "Некорректное время." }
        if (mode == ScheduleMode.TIMES) require(times.isNotEmpty()) { "Добавьте хотя бы одно время." }
        require(intervalHours in INTERVALS) { "Выберите интервал из списка." }
        require(windowStart in 0..1439 && windowEnd in 0..1439 && windowStart != windowEnd) {
            "Начало и конец окна должны отличаться."
        }
        require((quietStart == null) == (quietEnd == null)) { "Укажите начало и конец тихих часов." }
        if (quietStart != null && quietEnd != null) require(
            quietStart in 0..1439 && quietEnd in 0..1439 && quietStart != quietEnd
        ) { "Начало и конец тихих часов должны отличаться." }
    }

    fun isQuiet(time: LocalTime): Boolean {
        val start = quietStart ?: return false
        val end = quietEnd ?: return false
        val minute = time.hour * 60 + time.minute
        return if (start < end) minute >= start && minute < end else minute >= start || minute < end
    }

    /** Recheck actual delivery: Android can delay an inexact alarm beyond its allowed window. */
    fun allowsScheduledDelivery(at: Instant, zone: ZoneId): Boolean {
        val local = at.atZone(zone)
        val time = local.toLocalTime()
        if (isQuiet(time)) return false
        val minute = time.hour * 60 + time.minute
        val anchor = if (mode == ScheduleMode.INTERVAL && windowEnd < windowStart && minute <= windowEnd) {
            local.toLocalDate().minusDays(1)
        } else local.toLocalDate()
        if (daysMask and (1 shl (anchor.dayOfWeek.value - 1)) == 0) return false
        if (mode == ScheduleMode.TIMES) return true
        return if (windowStart < windowEnd) minute in windowStart..windowEnd
            else minute >= windowStart || minute <= windowEnd
    }

    companion object { val INTERVALS = listOf(1, 2, 3, 4, 6, 8, 12) }
}

/**
 * Strictly after now, at or after pause expiry. Wall-clock slots never drift with delivery.
 * Gap times move forward (as in 8.1); overlaps use only the earlier offset. Equal instants
 * caused by a gap are deduplicated. Quiet hours include their start and exclude their end.
 * Window endpoints are included. Null means every selected slot is excluded by quiet hours.
 */
fun nextScheduleTime(now: Instant, zone: ZoneId, schedule: ReminderSchedule, pausedUntil: Instant? = null): Instant? {
    schedule.validate()
    val lower = maxOf(now, pausedUntil ?: now)
    val date = lower.atZone(zone).toLocalDate()
    val candidates = sortedSetOf<Instant>()
    for (offset in -1L..8L) {
        val anchor = date.plusDays(offset)
        if (schedule.daysMask and (1 shl (anchor.dayOfWeek.value - 1)) == 0) continue
        val slots = if (schedule.mode == ScheduleMode.TIMES) schedule.times.toList() else {
            val end = schedule.windowEnd + if (schedule.windowEnd < schedule.windowStart) 1440 else 0
            (schedule.windowStart..end step schedule.intervalHours * 60).toList()
        }
        for (minute in slots) {
            // Quiet slots remain excluded even if a DST gap would shift them out of quiet hours.
            if (schedule.isQuiet(LocalTime.of((minute % 1440) / 60, minute % 60))) continue
            val candidate = anchor.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant()
            if (schedule.mode == ScheduleMode.INTERVAL) {
                val end = schedule.windowEnd + if (schedule.windowEnd < schedule.windowStart) 1440 else 0
                // A spring gap must not move a slot outside the permitted window.
                if (candidate.atZone(zone).toLocalDateTime() > anchor.atStartOfDay().plusMinutes(end.toLong())) continue
            }
            if (candidate > now && candidate >= lower && !schedule.isQuiet(candidate.atZone(zone).toLocalTime())) {
                candidates += candidate
            }
        }
    }
    return candidates.firstOrNull()
}
