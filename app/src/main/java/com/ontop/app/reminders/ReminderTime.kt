package com.ontop.app.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** DST gaps move forward; overlaps use the earlier offset and never deliver twice that day. */
fun nextReminderTime(now: Instant, zone: ZoneId, hour: Int, minute: Int): Instant {
    val time = LocalTime.of(hour, minute)
    val today = now.atZone(zone).toLocalDate()
    val candidate = today.atTime(time).atZone(zone).toInstant()
    return if (candidate > now) candidate else today.plusDays(1).atTime(time).atZone(zone).toInstant()
}
