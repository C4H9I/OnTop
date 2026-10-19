package com.ontop.app.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.reminders.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class BackupSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun safContractsConfirmationCancellationRestoreAndBoardRefresh() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val root = File(app.cacheDir, "backup-ui-${UUID.randomUUID()}")
        val documents = File(app.cacheDir, "backup-test-documents").apply { mkdirs() }
        val file = File(documents, "backup-ui-${UUID.randomUUID()}.ontop")
        var creates = 0; var opens = 0; var widgets = 0
        val reminders = ReminderManager(db, object : ReminderDelivery {
            override fun schedule(id: Long, snooze: Boolean, at: Long) = Unit
            override fun cancel(id: Long) = Unit
            override fun show(rule: ReminderEntity, card: CardEntity) = Unit
        })
        val backup = BackupManager(app, db, reminders, root, { widgets++ })
        lateinit var model: BoardViewModel
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(app, input)
                when (contract) {
                    is ActivityResultContracts.CreateDocument -> {
                        creates++; assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                        assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".ontop"))
                    }
                    is ActivityResultContracts.OpenDocument -> { opens++; assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action) }
                    else -> error("Unexpected SAF contract")
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.backupfixtures", file)
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, uri as O)
            }
        }
        try {
            val card = CardEntity(1, "Исходная версия", "Текст", createdAt = 1, updatedAt = 2, sortOrder = 0)
            db.cardDao().insert(card)
            instrumentation.runOnMainSync { model = BoardViewModel(app, db, backup) }
            root.mkdirs()
            val protected = File(root, "private-file").apply { writeText("Do not overwrite") }
            instrumentation.runOnMainSync { model.createBackup(Uri.fromFile(protected)) }
            assertEquals("Do not overwrite", protected.readText())
            assertNotNull(backup.state.value.error)
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) { OnTopTheme { BoardScreen(model, owner.lifecycle) } }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Меню приложения").performClick()
            compose.onNodeWithText("Настройки").performClick()
            compose.onNodeWithText("Резервное копирование").assertIsDisplayed()
            compose.onNodeWithText("Создать резервную копию").performClick()
            compose.waitUntil(10_000) { file.length() > 0 && !backup.state.value.busy }
            assertNull(backup.state.value.error); assertEquals(1, creates)
            db.cardDao().update(card.copy(title = "Текущая версия"))
            compose.onNodeWithText("Восстановить из файла").performClick()
            compose.waitUntil(10_000) { backup.state.value.ready != null && !backup.state.value.busy }
            compose.onNodeWithText("Заменить текущие данные?").assertIsDisplayed()
            assertEquals("Текущая версия", db.cardDao().findById(1)!!.title)
            compose.onNodeWithText("Отмена").performClick()
            compose.waitUntil(5_000) { backup.state.value.ready == null && !backup.state.value.busy }
            assertEquals("Текущая версия", db.cardDao().findById(1)!!.title)
            compose.onNodeWithText("Восстановить из файла").performClick()
            compose.waitUntil(10_000) { backup.state.value.ready != null && !backup.state.value.busy }
            compose.waitForIdle(); restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Заменить текущие данные?").assertIsDisplayed()
            compose.onNodeWithText("Заменить и восстановить").performClick()
            compose.waitUntil(10_000) { !backup.state.value.busy && backup.state.value.message.startsWith("Данные восстановлены") }
            assertNull(backup.state.value.error); assertEquals(1, widgets); assertEquals(2, opens)
            assertEquals("Исходная версия", db.cardDao().findById(1)!!.title)
            compose.onNodeWithText("Назад").performClick()
            compose.onNodeWithText("Исходная версия").assertIsDisplayed()
            compose.onNodeWithText("Просмотр →").performClick()
            compose.onNodeWithText("1 из 1").assertIsDisplayed()
            compose.onNodeWithText("Редактировать").performClick()
            compose.onNodeWithTag("editor-title").performTextReplacement("Устаревший черновик")
            // A replacement performed by another route must invalidate a draft even when IDs match.
            backup.prepare { file.inputStream() }; backup.restore()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            assertEquals("Исходная версия", db.cardDao().findById(1)!!.title)
            compose.onNodeWithText("Исходная версия").assertIsDisplayed()
        } finally {
            model.viewModelScope.cancel()
            backup.recover(); backup.discardPrepared()
            db.close(); root.deleteRecursively(); file.delete()
        }
    }
}
