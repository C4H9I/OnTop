package com.ontop.app.reminders

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.*

/** Short-lived system job, not a foreground or persistent service. */
class ReminderRestoreJob : JobService() {
    private var work: Job? = null
    companion object {
        fun enqueue(context: Context, rebase: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val shouldRebase = rebase || scheduler.getPendingJob(8101)?.extras?.getBoolean("rebase") == true
            val result = scheduler.schedule(
                JobInfo.Builder(8101, ComponentName(context, ReminderRestoreJob::class.java))
                    .setExtras(PersistableBundle().apply { putBoolean("rebase", shouldRebase) })
                    .setOverrideDeadline(0).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            if (result != JobScheduler.RESULT_SUCCESS) Log.e("OnTopReminder", "System rejected reminder reconciliation job")
        }
    }
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var retry = false
            try {
                if (getSystemService(UserManager::class.java).isUserUnlocked) {
                    val backup = com.ontop.app.backup.BackupManager.get(this@ReminderRestoreJob,
                        com.ontop.app.data.OnTopDatabase.getInstance(this@ReminderRestoreJob))
                    backup.recover()
                    check(!backup.state.value.recoveryRequired)
                    ReminderManager(this@ReminderRestoreJob).restore(params.extras.getBoolean("rebase"))
                } else retry = true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.e("OnTopReminder", "Could not restore reminders", e)
                retry = true
            }
            withContext(Dispatchers.Main) { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}

class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            // This receiver is NOT directBootAware: BOOT_COMPLETED is delivered after CE unlock.
            // USER_UNLOCKED is dynamic-only; no Room access is attempted in Direct Boot.
            ReminderRestoreJob.enqueue(context, rebase = true)
        }
    }
}
