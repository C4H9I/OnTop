package com.ontop.app.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import java.io.File

/** Launches the actual configuration Activity with a bound widget, including recreation. */
class WidgetTopicsConfigurationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun reconfigureMultiSelectionRecreationCancelAndExplicitAll() = runBlocking {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = OnTopDatabase.getInstance(context)
        val store = WidgetSelectionStore(context)
        val host = AppWidgetHost(context, (System.nanoTime() and 0x3fffffff).toInt())
        val tags = mutableListOf<Long>()
        var id = 0
        var card = 0L
        try {
            repeat(2) { tags += db.tagDao().create("Тема виджета ${System.nanoTime()} $it") }
            card = db.cardDao().insert(CardEntity(title = "Тематическая заметка", createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.tagDao().insertLink(CardTagCrossRef(card, tags[0]))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            id = host.allocateAppWidgetId()
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id,
                ComponentName(context, CardWidgetReceiver::class.java)))
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            WidgetRotation.configure(context, id, WidgetMode.ROTATION, tagIds = setOf(tags[0]))
            fun intent() = Intent(context, WidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<WidgetConfigurationActivity>(intent()).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-topic-${tags[0]}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-topic-${tags[0]}").assertIsSelected()
                compose.onNodeWithTag("widget-topic-${tags[1]}").performScrollTo().performClick()
                scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-topic-${tags[1]}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-topic-${tags[1]}").assertIsSelected()
                compose.onNodeWithTag("widget-topic-${tags[0]}").assertIsSelected()
                compose.waitForIdle()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "widget-topics-config.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                compose.onNodeWithText("Сохранить ротацию").performClick()
                compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            }
            val saved = store.selection(id)
            ActivityScenario.launch<WidgetConfigurationActivity>(intent()).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-topic-${tags[0]}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Одна заметка").performClick()
                compose.onNodeWithTag("widget-topics-all").assertDoesNotExist()
                compose.onNodeWithText("Отмена").performClick()
            }
            assertEquals(saved, store.selection(id))
            // Deleted topics must remain an empty filter when opening and saving unchanged.
            tags.forEach { db.tagDao().delete(it) }
            WidgetRotation.next(context, id)
            ActivityScenario.launch<WidgetConfigurationActivity>(intent()).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-topics-all").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Выбранные темы удалены. Выберите другие темы или все заметки.").assertIsDisplayed()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "widget-topics-empty.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                compose.onNodeWithText("Сохранить ротацию").performClick()
                compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            }
            assertTrue(store.selection(id).tagFilterEnabled)
            ActivityScenario.launch<WidgetConfigurationActivity>(intent()).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-topics-all").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-topics-all").performClick().assertIsSelected()
                compose.onNodeWithText("Сохранить ротацию").performClick()
                compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            }
            assertNotNull(store.get(id))
            assertEquals(WidgetMode.ROTATION, store.selection(id).mode)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (id > 0) WidgetRotation.deleted(context, id)
            host.deleteHost()
            if (card > 0) db.cardDao().findById(card)?.let { db.cardDao().delete(it) }
            tags.forEach { db.tagDao().delete(it) }
        }
    }
}
