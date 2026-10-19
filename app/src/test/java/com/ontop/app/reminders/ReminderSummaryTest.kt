package com.ontop.app.reminders

import com.ontop.app.data.ReminderEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReminderSummaryTest {
    private val rule = ReminderEntity(cardId = 1, hour = 9, minute = 0, enabled = true, createdAt = 1, updatedAt = 1)
    private val now = Instant.parse("2026-10-19T00:00:00Z")
    private val zone = ZoneId.of("UTC")

    @Test fun legacyDailyAndDisabledStatusArePreserved() {
        assertEquals("Ежедневно в 09:00", rule.summary(now, zone))
        assertEquals("Напоминание выключено", rule.copy(enabled = false).summary(now, zone))
    }

    @Test fun timesDaysIntervalQuietAndPauseAreHumanReadable() {
        assertEquals("Пн, Ср, Пт · 09:00, 18:00", rule.copy(daysMask = 21, times = "540,1080").summary(now, zone))
        assertEquals("Каждые 3 ч · 09:00–21:00", rule.copy(mode = "INTERVAL").summary(now, zone))
        val paused = rule.copy(mode = "INTERVAL", daysMask = 1, quietStart = 1320, quietEnd = 480,
            pausedUntil = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli())
        assertEquals("Пауза до 20.10.2026 12:00 · Каждые 3 ч · 09:00–21:00 · Пн · тихие часы 22:00–08:00", paused.summary(now, zone))
        assertFalse(paused.summary(Instant.parse("2026-10-20T12:00:00Z"), zone).startsWith("Пауза"))
    }
}
