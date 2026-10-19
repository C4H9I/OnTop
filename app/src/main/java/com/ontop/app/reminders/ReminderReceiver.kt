package com.ontop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.*

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in setOf(AndroidReminderDelivery.DAILY, AndroidReminderDelivery.SNOOZED,
                AndroidReminderDelivery.SNOOZE)) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val id = intent.getLongExtra("ruleId", 0).takeIf { it > 0 } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val backup = com.ontop.app.backup.BackupManager.get(context,
                        com.ontop.app.data.OnTopDatabase.getInstance(context))
                    backup.recover()
                    check(!backup.state.value.recoveryRequired)
                    val manager = ReminderManager(context)
                    if (action == AndroidReminderDelivery.SNOOZE) {
                        manager.snooze(id)
                        AndroidReminderDelivery(context).dismiss(id)
                    } else manager.fire(id, action == AndroidReminderDelivery.SNOOZED,
                        intent.getLongExtra("expectedAt", -1))
                }
            } catch (error: Exception) {
                Log.e("OnTopReminder", "Reminder processing failed; scheduling reconciliation", error)
                ReminderRestoreJob.enqueue(context)
            } finally { pending.finish() }
        }
    }
}
