package com.ontop.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetInteractionActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldWarmLegacyWidgetLaunchRecreationRotationAndReminderSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = OnTopDatabase.getInstance(context)
        val id = db.cardDao().insertAtEnd(CardEntity(title = "Widget event ${java.util.UUID.randomUUID()}",
            createdAt = 1, updatedAt = 1, sortOrder = 0))
        fun request(widget: Boolean) = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CARD_ID, id).apply {
                if (widget) putExtra(MainActivity.EXTRA_OPEN_SOURCE, "widget")
            }
        fun ageEvents() { db.openHelper.writableDatabase.execSQL(
            "UPDATE note_interactions SET timestamp = ? WHERE noteId = ?", arrayOf(System.currentTimeMillis() - 20000, id)) }
        fun count() = runBlocking { db.noteInteractionDao().count(id, "WIDGET_OPENED") }
        fun awaitCount(expected: Long) = compose.waitUntil(5000) { count() == expected }
        try {
            ActivityScenario.launch<MainActivity>(request(true)).use { scenario ->
                awaitCount(1)
                ageEvents() // Recreating must not rely on the 10-second dedup window.
                scenario.recreate()
                compose.onNodeWithTag("card-details").assertIsDisplayed()
                assertEquals(1L, count())
                val legacy = request(false).setData(Uri.parse("ontop://card/$id/widget/12345"))
                assertTrue(legacy.isWidgetNoteOpen())
                scenario.onActivity { it.startActivity(legacy.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                awaitCount(2)
                ageEvents()
                val reminder = request(false)
                assertFalse(reminder.isWidgetNoteOpen())
                scenario.onActivity { it.startActivity(reminder.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                compose.waitForIdle()
                assertEquals(2L, count())
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                compose.waitForIdle()
                compose.onNodeWithTag("card-details").assertIsDisplayed()
                assertEquals(2L, count())
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                assertEquals(0L, db.noteInteractionDao().count(id, "NOTE_OPENED"))
            }
        } finally { db.cardDao().findById(id)?.let { db.cardDao().delete(it) } }
    }
}
