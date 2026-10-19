package com.ontop.app.reminders

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTimeTest {
    private fun check(now: String, expected: String, zone: String = "Europe/Moscow", hour: Int = 9, minute: Int = 0) {
        assertEquals(Instant.parse(expected), nextReminderTime(Instant.parse(now), ZoneId.of(zone), hour, minute))
    }
    @Test fun todayBeforeTime() = check("2026-10-18T05:59:59Z", "2026-10-18T06:00:00Z")
    @Test fun equalitySchedulesTomorrow() = check("2026-10-18T06:00:00Z", "2026-10-19T06:00:00Z")
    @Test fun midnightCrossesYear() = check("2026-12-31T22:00:00Z", "2027-01-01T21:00:00Z", hour = 0)
    @Test fun springGapMovesForward() = check("2026-03-29T00:00:00Z", "2026-03-29T01:30:00Z", "Europe/Berlin", 2, 30)
    @Test fun springTomorrowIsNot24HoursLater() = check("2026-03-28T08:00:00Z", "2026-03-29T07:00:00Z", "Europe/Berlin")
    @Test fun autumnOverlapDoesNotRepeatSameDay() = check("2026-10-25T00:45:00Z", "2026-10-26T01:30:00Z", "Europe/Berlin", 2, 30)
    @Test fun newZoneChangesUtcTime() = check("2026-10-18T05:00:00Z", "2026-10-18T09:00:00Z", "UTC")
    @Test(expected = java.time.DateTimeException::class) fun invalidTimeRejected() {
        nextReminderTime(Instant.EPOCH, ZoneId.of("UTC"), 24, 0)
    }
}
