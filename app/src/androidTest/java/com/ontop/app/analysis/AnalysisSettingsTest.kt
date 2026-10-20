package com.ontop.app.analysis

import android.app.Application
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.backup.BackupManager
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.time.LocalDate

@RunWith(Parameterized::class)
class AnalysisSettingsTest(private val width: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "portrait-{0}dp")
        fun widths() = listOf(arrayOf(360), arrayOf(393))
    }
    @get:Rule val compose = createComposeRule()

    @Test fun privacyWarningSafJsonContractCancellationAndSuccessfulLocalWrite() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val root = File(app.cacheDir, "analysis-ui-${System.nanoTime()}")
        val documents = File(app.cacheDir, "backup-test-documents").apply { mkdirs() }
        val file = File(documents, "analysis-${System.nanoTime()}.json")
        val backup = BackupManager(app, db, root = root, refreshWidgets = {})
        lateinit var model: BoardViewModel
        var creates = 0
        var cancelPicker = true
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.CreateDocument)
                val intent = contract.createIntent(app, input)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/json", intent.type)
                assertEquals("ontop-analysis-${LocalDate.now()}.json", intent.getStringExtra(Intent.EXTRA_TITLE))
                creates++
                val uri = if (cancelPicker) null else FileProvider.getUriForFile(app, "${app.packageName}.backupfixtures", file)
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, uri as O)
            }
        }
        try {
            db.cardDao().insert(CardEntity(1, "Личная мысль", "Текст 🧠", createdAt = 0, updatedAt = 1000, sortOrder = 0))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db, backup) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) { OnTopTheme { Box(Modifier.width(width.dp).fillMaxHeight()) {
                    val state by backup.state.collectAsState()
                    BackupSettingsScreen(model, state, {})
                } } }
            }
            compose.onNodeWithTag("analysis-export").performScrollTo().performClick()
            compose.onNodeWithText("JSON может содержать личную информацию").assertIsDisplayed()
            assertEquals(0, creates)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("JSON может содержать личную информацию").assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(0, creates); assertFalse(file.exists())
            compose.onNodeWithTag("analysis-export").performScrollTo().performClick()
            compose.onNodeWithText("Выбрать файл").performClick()
            compose.onNodeWithTag("analysis-message").assertTextEquals("Экспорт отменён")
            assertEquals(1, creates); assertFalse(file.exists())
            cancelPicker = false
            compose.onNodeWithTag("analysis-export").performScrollTo().performClick()
            compose.onNodeWithText("Выбрать файл").performClick()
            compose.waitUntil(15_000) { !model.analysis.state.value.busy && file.length() > 0 }
            assertNull(model.analysis.state.value.error)
            val note = JSONObject(file.readText()).getJSONArray("notes").getJSONObject(0)
            assertEquals("Личная мысль", note.getString("title"))
            assertEquals("Текст 🧠", note.getString("content"))
            assertEquals(2, creates)
            assertEquals(0, db.cardCommentDao().all().size)
            assertEquals(50, db.cardDao().findById(1)!!.importance)
            compose.onNodeWithTag("analysis-message").performScrollTo().assertIsDisplayed()
        } finally {
            model.viewModelScope.cancel()
            db.close(); file.delete(); root.deleteRecursively()
        }
    }
}
