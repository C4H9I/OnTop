package com.ontop.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NoteStatisticsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realDetailsSheetSurvivesRecreationAndRotationWithoutEvents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = OnTopDatabase.getInstance(context)
        val id = db.cardDao().insertAtEnd(CardEntity(title = "Статистика ${java.util.UUID.randomUUID()}",
            createdAt = 1, updatedAt = 123, sortOrder = 0))
        try {
            val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CARD_ID, id)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                compose.onNodeWithContentDescription("Статистика заметки").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("История взаимодействий пока отсутствует").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("statistics-opened").assertTextEquals("0")
                compose.onNodeWithTag("statistics-last-visit").performScrollTo().assertTextEquals("Нет данных")
                compose.onNodeWithText("Статистика внимания").performScrollTo()
                val path = File(context.getExternalFilesDir(null), "note-statistics").apply { mkdirs() }
                File(path, "sheet.png").outputStream().use {
                    compose.onAllNodes(isDialog())[1].captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                scenario.recreate()
                compose.onNodeWithText("Статистика внимания").assertIsDisplayed()
                val repository = NoteInteractionRepository(db)
                repository.record(id, NoteInteractionType.WIDGET_OPENED, 100)
                compose.waitUntil(10000) { compose.onAllNodesWithText("История взаимодействий пока отсутствует").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("statistics-widget").assertTextEquals("1")
                compose.onNodeWithTag("statistics-visits").performScrollTo().assertTextEquals("1")
                compose.onNodeWithText("Статистика внимания").performScrollTo()
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                compose.onNodeWithText("Статистика внимания").assertIsDisplayed()
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                compose.onNodeWithText("Статистика внимания").assertIsDisplayed()
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithContentDescription("Статистика заметки").assertIsDisplayed()
                compose.onNodeWithContentDescription("Статистика заметки").performClick()
                compose.onNodeWithText("Статистика внимания").assertIsDisplayed()
                assertEquals(1, db.noteInteractionDao().history(id).size)
                assertEquals(123L, db.cardDao().findById(id)!!.updatedAt)
            }
        } finally { db.cardDao().findById(id)?.let { db.cardDao().delete(it) } }
    }
}
