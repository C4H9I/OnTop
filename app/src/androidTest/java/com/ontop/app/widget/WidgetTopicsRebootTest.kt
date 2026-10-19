package com.ontop.app.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit two-phase emulator check; skipped in an ordinary suite. Reboot between phases. */
class WidgetTopicsRebootTest {
    @Test fun persistedThematicWidgetAcrossActualEmulatorReboot() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("widgetRebootPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = OnTopDatabase.getInstance(context)
        val store = WidgetSelectionStore(context)
        val work = WorkManager.getInstance(context)
        val fixture = context.getSharedPreferences("widget-topics-reboot-fixture", 0)
        if (phase == "prepare") {
            assumeTrue(store.rotationIds().intersect(WidgetRotation.activeIds(context)).isEmpty())
            check(!fixture.contains("widget")) { "Finish the previous reboot fixture first" }
            val hostId = (System.nanoTime() and 0x3fffffff).toInt()
            val host = AppWidgetHost(context, hostId)
            val tag = db.tagDao().create("Reboot fixture ${System.nanoTime()}")
            val card = db.cardDao().insert(CardEntity(title = "After reboot", createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.tagDao().insertLink(CardTagCrossRef(card, tag))
            var id = 0
            try {
                instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                id = host.allocateAppWidgetId()
                assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id,
                    ComponentName(context, CardWidgetReceiver::class.java)))
                instrumentation.uiAutomation.dropShellPermissionIdentity()
                WidgetRotation.configure(context, id, WidgetMode.ROTATION, tagIds = setOf(tag))
                val periodic = work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME).get().single { !it.state.isFinished }
                check(fixture.edit().putInt("host", hostId).putInt("widget", id).putLong("tag", tag)
                    .putLong("card", card).putString("work", periodic.id.toString())
                    .putInt("boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)).commit())
            } catch (failure: Throwable) {
                if (id > 0) WidgetRotation.deleted(context, id)
                host.deleteHost()
                db.cardDao().findById(card)?.let { db.cardDao().delete(it) }
                db.tagDao().delete(tag)
                throw failure
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        } else {
            check(fixture.contains("widget")) { "Run the prepare phase before reboot" }
            val id = fixture.getInt("widget", 0)
            val card = fixture.getLong("card", 0)
            val tag = fixture.getLong("tag", 0)
            try {
                assertTrue(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) > fixture.getInt("boot", -1))
                assertTrue(id in WidgetRotation.activeIds(context))
                assertEquals(WidgetMode.ROTATION, store.selection(id).mode)
                assertEquals(setOf(tag), store.selection(id).tagIds)
                assertTrue(store.selection(id).tagFilterEnabled)
                assertEquals(card, store.get(id))
                WidgetRotation.reconcile(context)
                assertEquals(fixture.getString("work", null), work.getWorkInfosForUniqueWork(WidgetRotation.WORK_NAME)
                    .get().single { !it.state.isFinished }.id.toString())
                WidgetRotation.next(context, id)
                assertEquals(card, store.get(id))
            } finally {
                WidgetRotation.deleted(context, id)
                AppWidgetHost(context, fixture.getInt("host", 0)).deleteHost()
                db.cardDao().findById(card)?.let { db.cardDao().delete(it) }
                db.tagDao().delete(tag)
                check(fixture.edit().clear().commit())
            }
        }
    }
}
