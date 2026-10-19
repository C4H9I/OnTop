package com.ontop.app.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReminderScheduleTest {
    private val utc = ZoneId.of("UTC")
    private fun next(now: String, rule: ReminderSchedule, zone: ZoneId = utc, pause: String? = null) =
        nextScheduleTime(Instant.parse(now), zone, rule, pause?.let(Instant::parse))

    @Test fun selectedDaysMultipleTimesAndStrictBoundary() {
        val rule = ReminderSchedule(daysMask = 1 or 4 or 16, times = setOf(540, 1080))
        assertEquals(Instant.parse("2026-10-19T18:00:00Z"), next("2026-10-19T09:00:00Z", rule))
        assertEquals(Instant.parse("2026-10-21T09:00:00Z"), next("2026-10-19T18:00:00Z", rule))
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), next("2026-10-23T18:00:00Z", rule))
    }

    @Test fun everySupportedIntervalIsAnchoredAndIncludesWindowEnd() {
        ReminderSchedule.INTERVALS.forEach { hours ->
            val rule = ReminderSchedule(mode = ScheduleMode.INTERVAL, intervalHours = hours, windowStart = 480, windowEnd = 1200)
            assertEquals(Instant.parse("2026-10-19T08:00:00Z").plusSeconds(hours * 3600L), next("2026-10-19T08:17:00Z", rule))
            assertEquals(Instant.parse("2026-10-20T08:00:00Z"), next("2026-10-19T20:00:00Z", rule))
        }
    }

    @Test fun overnightIntervalBelongsToStartDayAndDoesNotDrift() {
        val rule = ReminderSchedule(mode = ScheduleMode.INTERVAL, daysMask = 1, intervalHours = 3, windowStart = 1260, windowEnd = 420)
        assertEquals(Instant.parse("2026-10-20T03:00:00Z"), next("2026-10-20T00:37:00Z", rule))
        assertEquals(Instant.parse("2026-10-20T06:00:00Z"), next("2026-10-20T03:01:00Z", rule))
        assertEquals(Instant.parse("2026-10-26T21:00:00Z"), next("2026-10-20T06:00:00Z", rule))
    }

    @Test fun quietHoursSkipAcrossMidnightAndExcludeEnd() {
        val rule = ReminderSchedule(times = setOf(0, 420, 480, 1320), quietStart = 1320, quietEnd = 480)
        assertTrue(rule.isQuiet(LocalTime.of(22, 0)))
        assertTrue(rule.isQuiet(LocalTime.of(7, 59)))
        assertFalse(rule.isQuiet(LocalTime.of(8, 0)))
        assertEquals(Instant.parse("2026-10-20T08:00:00Z"), next("2026-10-19T21:59:00Z", rule))
        assertNull(next("2026-10-19T00:00:00Z", rule.copy(times = setOf(0, 420))))
    }

    @Test fun daytimeQuietHoursAndPauseExpiryDoNotCreateBacklog() {
        val rule = ReminderSchedule(times = setOf(540, 720, 1080), quietStart = 600, quietEnd = 1020)
        assertEquals(Instant.parse("2026-10-19T18:00:00Z"), next("2026-10-19T09:00:00Z", rule))
        assertEquals(Instant.parse("2026-11-02T18:00:00Z"), next("2026-10-19T09:00:00Z", rule, pause = "2026-11-02T18:00:00Z"))
        assertEquals(Instant.parse("2026-11-03T09:00:00Z"), next("2026-10-19T09:00:00Z", rule, pause = "2026-11-02T18:01:00Z"))
    }

    @Test fun springGapAndCoincidentSlotsDeliverOnce() {
        val zone = ZoneId.of("Europe/Berlin")
        val rule = ReminderSchedule(times = setOf(150, 210))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), next("2026-03-29T00:00:00Z", rule, zone))
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"), next("2026-03-29T01:30:00Z", rule, zone))
    }

    @Test fun autumnOverlapDoesNotRepeatAndIntervalStaysOnLocalGrid() {
        val zone = ZoneId.of("Europe/Berlin")
        val rule = ReminderSchedule(mode = ScheduleMode.INTERVAL, intervalHours = 1, windowStart = 60, windowEnd = 240)
        assertEquals(Instant.parse("2026-10-25T02:00:00Z"), next("2026-10-25T00:05:00Z", rule, zone))
        assertEquals(Instant.parse("2026-03-29T02:00:00Z"), next("2026-03-29T01:00:00Z", rule, zone))
    }

    @Test fun springGapCannotMoveIntervalOutsideWindow() {
        val rule = ReminderSchedule(mode = ScheduleMode.INTERVAL, intervalHours = 1, windowStart = 60, windowEnd = 150)
        assertEquals(Instant.parse("2026-03-29T23:00:00Z"), next("2026-03-29T00:00:00Z", rule, ZoneId.of("Europe/Berlin")))
    }

    @Test fun springGapCannotEscapeQuietHours() {
        val rule = ReminderSchedule(times = setOf(150), quietStart = 120, quietEnd = 180)
        assertNull(next("2026-03-29T00:00:00Z", rule, ZoneId.of("Europe/Berlin")))
    }

    @Test fun lateDeliveryMustStillRespectDaysAndOvernightWindow() {
        val rule = ReminderSchedule(mode = ScheduleMode.INTERVAL, daysMask = 1, windowStart = 1260, windowEnd = 420)
        assertTrue(rule.allowsScheduledDelivery(Instant.parse("2026-10-20T06:30:00Z"), utc))
        assertFalse(rule.allowsScheduledDelivery(Instant.parse("2026-10-20T07:01:00Z"), utc))
        assertFalse(rule.allowsScheduledDelivery(Instant.parse("2026-10-20T21:00:00Z"), utc))
        assertFalse(rule.copy(mode = ScheduleMode.TIMES).allowsScheduledDelivery(Instant.parse("2026-10-20T06:30:00Z"), utc))
    }

    @Test fun oldDailySemanticsMatchAcrossZonesAndDst() {
        listOf("UTC", "Europe/Moscow", "Europe/Berlin", "America/New_York").forEach { name ->
            val zone = ZoneId.of(name)
            listOf("2026-03-29T00:30:00Z", "2026-10-25T00:45:00Z", "2026-12-31T23:59:00Z").forEach { value ->
                val now = Instant.parse(value)
                assertEquals(nextReminderTime(now, zone, 2, 30), nextScheduleTime(now, zone, ReminderSchedule(times = setOf(150))))
            }
        }
    }

    @Test fun invalidInputsRejectedAndDuplicateTimesCollapse() {
        assertEquals(1, ReminderSchedule(times = setOf(540, 540)).times.size)
        listOf(ReminderSchedule(daysMask = 0), ReminderSchedule(times = emptySet()),
            ReminderSchedule(times = setOf(1440)), ReminderSchedule(intervalHours = 5),
            ReminderSchedule(windowStart = 540, windowEnd = 540),
            ReminderSchedule(quietStart = 60), ReminderSchedule(quietStart = 60, quietEnd = 60)).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
    }
}
