package com.ontop.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.*
import androidx.room.withTransaction
import com.ontop.app.backup.BackupManager
import com.ontop.app.data.OnTopDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object WidgetRotation {
    const val WORK_NAME = "ontop-widget-rotation"
    const val INTERVAL_MS = 30L * 60 * 1000
    fun activeIds(context: Context): Set<Int> = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, CardWidgetReceiver::class.java)).toSet()

    // All entry points use backup lock -> widget lock. Restore holds the former already.
    suspend fun configure(context: Context, id: Int, mode: WidgetMode, cardId: Long? = null,
        tagIds: Set<Long> = emptySet(), tagFilterEnabled: Boolean = tagIds.isNotEmpty()) =
        BackupManager.dataAccess { WidgetSelectionStore.mutex.withLock {
            check(id in activeIds(context))
            val db = OnTopDatabase.getInstance(context)
            val store = WidgetSelectionStore(context)
            val previous = store.selection(id)
            val chosen = if (mode == WidgetMode.FIXED) {
                require(cardId != null && db.cardDao().findById(cardId) != null); cardId
            } else previous.currentId.takeIf { previous.mode == WidgetMode.ROTATION }
            store.save(id, WidgetSelection(mode, if (mode == WidgetMode.FIXED) chosen else null,
                currentId = if (mode == WidgetMode.ROTATION) chosen else null,
                changedAt = if (mode == WidgetMode.ROTATION && previous.mode == WidgetMode.ROTATION)
                    previous.changedAt else System.currentTimeMillis(),
                tagIds = if (mode == WidgetMode.ROTATION) tagIds else emptySet(),
                tagFilterEnabled = mode == WidgetMode.ROTATION && tagFilterEnabled))
            if (mode == WidgetMode.ROTATION) select(store, db, id, false)
            reconcileLocked(context)
            updateCardWidget(context, id)
        } }

    /** force=false repairs missing/ineligible cards while keeping a still eligible selection. */
    internal suspend fun select(store: WidgetSelectionStore, db: OnTopDatabase, id: Int,
        force: Boolean, now: Long = System.currentTimeMillis(), minimumGap: Long = 1000): Boolean {
        val previous = store.selection(id)
        if (previous.mode != WidgetMode.ROTATION) return false
        val (state, cards) = db.withTransaction {
            val tags = if (previous.tagFilterEnabled) previous.tagIds.intersect(db.tagDao().all().map { it.id }.toSet()) else emptySet()
            val cleaned = previous.copy(tagIds = tags)
            cleaned to rotationCandidates(db.cardDao().allCards(),
                if (cleaned.tagFilterEnabled) db.tagDao().allLinks() else emptyList(), tags, cleaned.tagFilterEnabled)
        }
        val exists = cards.any { it.id == state.currentId }
        if (exists && (!force || now >= state.changedAt && now - state.changedAt < minimumGap)) {
            if (state != previous) store.save(id, state)
            return state != previous
        }
        val chosen = chooseRotationNote(cards, state.currentId)?.id
        val next = if (chosen == state.currentId) state else state.copy(currentId = chosen, changedAt = now)
        if (next == previous) return false
        store.save(id, next)
        return true
    }

    suspend fun next(context: Context, id: Int) = BackupManager.dataAccess {
        WidgetSelectionStore.mutex.withLock {
            if (id !in activeIds(context)) return@withLock
            select(WidgetSelectionStore(context), OnTopDatabase.getInstance(context), id, true)
            updateCardWidget(context, id)
        }
    }

    suspend fun tick(context: Context) = BackupManager.dataAccess {
        WidgetSelectionStore.mutex.withLock {
            val store = WidgetSelectionStore(context)
            val db = OnTopDatabase.getInstance(context)
            for (id in store.rotationIds().intersect(activeIds(context))) {
                // Manual updates never reset the global periodic schedule; skip a recent per-widget change.
                select(store, db, id, true, minimumGap = INTERVAL_MS)
                updateCardWidget(context, id)
            }
            reconcileLocked(context)
        }
    }

    suspend fun reconcile(context: Context) = WidgetSelectionStore.mutex.withLock { reconcileLocked(context) }

    private suspend fun reconcileLocked(context: Context) = withContext(Dispatchers.IO) {
        val work = WorkManager.getInstance(context)
        if (WidgetSelectionStore(context).rotationIds().intersect(activeIds(context)).isEmpty()) {
            work.cancelUniqueWork(WORK_NAME).result.get()
        } else {
            val request = PeriodicWorkRequestBuilder<WidgetRotationWorker>(30, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.MINUTES).build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request).result.get()
        }
    }

    suspend fun deleted(context: Context, id: Int) = WidgetSelectionStore.mutex.withLock {
        WidgetSelectionStore(context).remove(id)
        reconcileLocked(context)
    }

    /** Caller already holds backup data lock (foreground edit or restore). */
    suspend fun refreshAfterEdit(context: Context, cardId: Long) = WidgetSelectionStore.mutex.withLock {
        val store = WidgetSelectionStore(context)
        val active = activeIds(context)
        val ids = (store.idsFor(cardId).toSet() + store.rotationIds()).intersect(active)
        for (id in ids) {
            select(store, OnTopDatabase.getInstance(context), id, false)
            updateCardWidget(context, id)
        }
    }
}

class WidgetRotationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val db = OnTopDatabase.getInstance(applicationContext)
        val backup = BackupManager.get(applicationContext, db)
        backup.recover()
        check(!backup.state.value.recoveryRequired)
        WidgetRotation.tick(applicationContext)
        Result.success()
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) { Result.retry() }
}

class NextWidgetNote : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetRotation.next(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}
