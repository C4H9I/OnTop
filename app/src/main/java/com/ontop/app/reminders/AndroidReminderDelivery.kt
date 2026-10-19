package com.ontop.app.reminders

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ontop.app.MainActivity
import com.ontop.app.R
import com.ontop.app.data.CardEntity
import com.ontop.app.data.ReminderEntity

internal class AndroidReminderDelivery(private val context: Context) : ReminderDelivery {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    companion object {
        const val CHANNEL = "daily_card_reminders"
        const val DAILY = "com.ontop.app.reminder.DAILY"
        const val SNOOZED = "com.ontop.app.reminder.SNOOZED"
        const val SNOOZE = "com.ontop.app.reminder.SNOOZE"
    }

    internal fun intent(id: Long, action: String) = Intent(context, ReminderReceiver::class.java)
        .setAction(action).setData(Uri.parse("ontop://reminder/$id/$action"))
        .putExtra("ruleId", id)

    private fun pending(id: Long, snooze: Boolean, at: Long) = PendingIntent.getBroadcast(context, 0,
        intent(id, if (snooze) SNOOZED else DAILY).putExtra("expectedAt", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun schedule(id: Long, snooze: Boolean, at: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at, System.currentTimeMillis() + 1000),
            pending(id, snooze, at))
    }

    override fun cancel(id: Long) {
        cancelAlarms(id)
        dismiss(id)
    }

    override fun cancelAlarms(id: Long) {
        for (snooze in listOf(false, true)) {
            val operation = pending(id, snooze, 0)
            alarms.cancel(operation)
            operation.cancel()
        }
    }

    fun dismiss(id: Long) { NotificationManagerCompat.from(context).cancel("reminder-$id", 1) }

    override fun show(rule: ReminderEntity, card: CardEntity) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания о карточках",
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Важные мысли из ваших карточек OnTop" })
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("ontop://card/${card.id}/reminder/${rule.id}"))
            .putExtra(MainActivity.EXTRA_CARD_ID, card.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, 0, intent(rule.id, SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OnTop — важная мысль").setContentText(card.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.title))
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, "Отложить на 10 минут", snooze).build()
        try { manager.notify("reminder-${rule.id}", 1, notification) } catch (_: SecurityException) {
            // Permission may have been revoked between the check and notify().
        }
    }
}
