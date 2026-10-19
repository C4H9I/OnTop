package com.ontop.app.reminders

import android.app.PendingIntent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReminderPendingIntentTest {
    @Test fun separateRulesAndSnoozesHaveDistinctIdentitiesAndAreCancelled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val delivery = AndroidReminderDelivery(context)
        val ids = listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE - 2)
        val at = System.currentTimeMillis() + 86_400_000
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        try {
            ids.forEach { id -> delivery.schedule(id, false, at); delivery.schedule(id, true, at) }
            val intents = ids.flatMap { id -> listOf(AndroidReminderDelivery.DAILY, AndroidReminderDelivery.SNOOZED).map { action ->
                PendingIntent.getBroadcast(context, 0, delivery.intent(id, action), flags)
            } }
            assertTrue(intents.all { it != null })
            assertEquals(4, intents.toSet().size)
            delivery.cancel(ids[0])
            assertNull(PendingIntent.getBroadcast(context, 0, delivery.intent(ids[0], AndroidReminderDelivery.DAILY), flags))
            assertNull(PendingIntent.getBroadcast(context, 0, delivery.intent(ids[0], AndroidReminderDelivery.SNOOZED), flags))
            assertNotNull(PendingIntent.getBroadcast(context, 0, delivery.intent(ids[1], AndroidReminderDelivery.DAILY), flags))
        } finally { ids.forEach(delivery::cancel) }
    }
}
