package com.ontop.app.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.ontop.app.data.CardEntity
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.data.CardTagCrossRef
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Uses temporary real Android widget instances; intentionally emulator-only. */
@RunWith(Parameterized::class)
class WidgetRotationLifecycleTest(private val themed: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "themed-{0}")
        fun modes() = listOf(arrayOf(false), arrayOf(true))
    }
    @Test fun realWidgetsScheduleOneWorkerSwitchIndependentlyAndCancelAfterLastDeletion() = runBlocking {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = WidgetSelectionStore(context)
        assumeTrue(store.rotationIds().intersect(WidgetRotation.activeIds(context)).isEmpty())
        val db = OnTopDatabase.getInstance(context)
        val work = WorkManager.getInstance(context)
        val host = AppWidgetHost(context, (System.nanoTime() and 0x3fffffff).toInt())
        val widgets = mutableListOf<Int>()
        val cards = mutableListOf<Long>()
        val tags = mutableListOf<Long>()
        val immediateWork = "rotation-test-${System.nanoTime()}"
        try {
            repeat(2) { i -> cards += db.cardDao().insert(CardEntity(title = "Rotation fixture $i",
                createdAt = 1, updatedAt = 1, sortOrder = 0, importance = i * 100)) }
            if (themed) {
                repeat(2) { tags += db.tagDao().create("Widget fixture ${System.nanoTime()}-$it") }
                cards += db.cardDao().insert(CardEntity(title = "Other theme", createdAt = 1, updatedAt = 1, sortOrder = 0))
                cards.take(2).forEach { db.tagDao().insertLink(CardTagCrossRef(it, tags[0])) }
                db.tagDao().insertLink(CardTagCrossRef(cards[1], tags[1]))
                db.tagDao().insertLink(CardTagCrossRef(cards[2], tags[1]))
            }
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            repeat(3) {
                val id = host.allocateAppWidgetId().also { widgets += it }
                assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id,
                    ComponentName(context, CardWidgetReceiver::class.java)))
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            val fixed = widgets[0]; val first = widgets[1]; val second = widgets[2]
            WidgetRotation.configure(context, fixed, WidgetMode.FIXED, cards[0])
            WidgetRotation.configure(context, first, WidgetMode.ROTATION, tagIds = tags.take(1).toSet())
            val periodic = work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME).get()
                .single { !it.state.isFinished }
            WidgetRotation.configure(context, second, WidgetMode.ROTATION, tagIds = tags.drop(1).toSet())
            WidgetRotation.reconcile(context)
            assertEquals(listOf(periodic.id), work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME).get()
                .filter { !it.state.isFinished }.map { it.id })
            val previous = store.get(first)
            val other = store.selection(second)
            // Remove the debounce delay before invoking the actual Glance callback.
            store.save(first, store.selection(first).copy(changedAt = 0))
            NextWidgetNote().onAction(context, GlanceAppWidgetManager(context).getGlanceIdBy(first), actionParametersOf())
            assertNotEquals(previous, store.get(first))
            if (themed) assertTrue(store.get(first) in cards.take(2))
            assertEquals(other, store.selection(second))
            assertEquals(cards[0], store.get(fixed))
            val beforeTick = store.get(first)
            store.save(first, store.selection(first).copy(changedAt = 0))
            store.save(second, store.selection(second).copy(changedAt = 0))
            // Execute the production worker through WorkManager without a 30-minute wait.
            work.enqueueUniqueWork(immediateWork, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetRotationWorker>().build()).result.get()
            withTimeout(60_000) {
                while (work.getWorkInfosForUniqueWork(immediateWork).get().none { it.state.isFinished }) delay(250)
            }
            assertEquals(WorkInfo.State.SUCCEEDED, work.getWorkInfosForUniqueWork(immediateWork).get().single().state)
            assertNotEquals(beforeTick, store.get(first))
            if (themed) {
                assertTrue(store.get(first) in cards.take(2))
                assertTrue(store.get(second) in cards.drop(1))
                WidgetRotation.configure(context, first, WidgetMode.ROTATION, tagIds = tags.toSet())
                assertEquals(WidgetMode.ROTATION, store.selection(first).mode)
                assertEquals(tags.toSet(), store.selection(first).tagIds)
                tags.forEach { db.tagDao().delete(it) }
                WidgetRotation.next(context, first)
                assertNull(store.get(first))
                assertTrue(store.selection(first).tagFilterEnabled)
                assertTrue(store.selection(first).tagIds.isEmpty())
            }
            assertEquals(cards[0], store.get(fixed))
            assertEquals(periodic.id, work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME).get()
                .single { !it.state.isFinished }.id)
            WidgetRotation.deleted(context, first)
            host.deleteAppWidgetId(first)
            assertEquals(WidgetMode.ROTATION, store.selection(second).mode)
            assertTrue(work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME).get().any { !it.state.isFinished })
            WidgetRotation.deleted(context, second)
            host.deleteAppWidgetId(second)
            assertTrue(work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME).get().all { it.state.isFinished })
            assertEquals(cards[0], store.get(fixed))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            work.cancelUniqueWork(immediateWork).result.get()
            widgets.forEach { WidgetRotation.deleted(context, it) }
            host.deleteHost()
            cards.forEach { id -> db.cardDao().findById(id)?.let { db.cardDao().delete(it) } }
            tags.forEach { db.tagDao().delete(it) }
        }
    }
}
