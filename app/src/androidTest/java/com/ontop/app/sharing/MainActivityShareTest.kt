package com.ontop.app.sharing

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.BoardViewModel
import com.ontop.app.MainActivity
import com.ontop.app.data.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Production Activity/Room, with only uniquely named emulator fixtures; never runs on a phone. */
class MainActivityShareTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun emulatorOnly() = assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
    private fun send(title: String, text: String) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun awaitEditor(title: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("editor-title") and hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-title").assertTextEquals(title)
    }
    private suspend fun nativeChooserAndBack(screenshotName: String) {
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            withTimeout(15_000) {
                while (!shell("dumpsys window").lineSequence().any {
                    it.contains("mCurrentFocus") && it.contains("com.android.intentresolver")
                }) delay(200)
            }
            withTimeout(15_000) {
                while (instrumentation.uiAutomation.rootInActiveWindow?.let {
                    it.packageName?.toString() == "com.android.intentresolver" && it.childCount > 0
                } != true) delay(200)
            }
            // Allow the native preview and entrance animation to settle.
            delay(750)
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(context.getExternalFilesDir(null), screenshotName).outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            shell("input keyevent 4")
            withTimeout(10_000) {
                while (!shell("dumpsys window").lineSequence().any {
                    it.contains("mCurrentFocus") && it.contains("com.ontop.app")
                }) delay(200)
            }
        }
    }

    @Test fun coldAndWarmTextDeliveryRecreationAndQueuedShareDoNotDuplicateOrOverwriteDraft() = runBlocking {
        emulatorOnly()
        val db = OnTopDatabase.getInstance(context)
        val prefix = "Share UI ${System.nanoTime()}"
        val title = "$prefix first"
        val body = "https://example.com/article\nПолучено из браузера 🧠"
        try {
            ActivityScenario.launch<MainActivity>(send(title, body)).use { scenario ->
                awaitEditor(title)
                compose.onNodeWithTag("editor-title").performTextReplacement("$title edited")
                scenario.recreate()
                awaitEditor("$title edited")
                compose.onNodeWithTag("editor-save").performClick()
                compose.waitUntil(15_000) { runBlocking { db.cardDao().allCards().any { it.title == "$title edited" } } }
                scenario.onActivity { it.startActivity(send("$prefix second", "Текст из мессенджера")) }
                awaitEditor("$prefix second")
                scenario.onActivity { it.startActivity(send("$prefix third", "Следующий материал")) }
                compose.waitForIdle()
                compose.onNodeWithTag("editor-title").assertTextEquals("$prefix second")
                scenario.recreate()
                awaitEditor("$prefix second")
                compose.onNodeWithTag("editor-save").performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("$prefix third").fetchSemanticsNodes().isNotEmpty()
                }
                awaitEditor("$prefix third")
                compose.onNodeWithContentDescription("Назад").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
                scenario.recreate()
                compose.onNodeWithTag("card-editor").assertDoesNotExist()
                val saved = db.cardDao().allCards().filter { it.title.startsWith(prefix) }
                assertEquals(2, saved.size)
                assertEquals(body, saved.single { it.title == "$title edited" }.description)
                compose.onNodeWithTag("card-board").performScrollToNode(hasTestTag("card-${saved.first().id}"))
                compose.onNodeWithTag("card-${saved.first().id}").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("share-note").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Поделиться").performClick()
                nativeChooserAndBack("share-text-chooser.png")
            }
        } finally { db.cardDao().allCards().filter { it.title.startsWith(prefix) }.forEach { db.cardDao().delete(it) } }
    }

    @Test fun imageCancellationCleanupLocalPersistenceActivityRestartAndOutgoingChooser() = runBlocking {
        emulatorOnly()
        val db = OnTopDatabase.getInstance(context)
        val prefix = "Share image UI ${System.nanoTime()}"
        val file = File(File(context.cacheDir, "backup-test-documents").apply { mkdirs() }, "$prefix.jpg")
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
        val bytes = file.readBytes()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.backupfixtures", file)
        val imported = mutableSetOf<String>()
        val chooser = AtomicReference<Intent?>()
        var outboundUri: Uri? = null
        var nativeUri: Uri? = null
        var blockChooser = true
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) {
                    chooser.set(intent)
                    if (blockChooser) return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        fun incoming(title: String, extra: Boolean) = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, "Подпись из галереи").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("image", uri); if (extra) putExtra(Intent.EXTRA_STREAM, uri) }
        var cardId: Long? = null
        try {
            ActivityScenario.launch<MainActivity>(incoming("$prefix cancel", true)).use { scenario ->
                awaitEditor("$prefix cancel")
                lateinit var model: BoardViewModel
                scenario.onActivity { model = ViewModelProvider(it)[BoardViewModel::class.java] }
                compose.waitUntil(15_000) { model.sharedInlineImage.value != null && !model.state.value.saving }
                val cancelledPath = model.sharedInlineImage.value!!.second
                assertNull(model.state.value.draftImagePath)
                compose.waitUntil(10_000) { compose.onAllNodes(hasTestTagPrefix("inline-image-")).fetchSemanticsNodes().size == 1 }
                imported += cancelledPath
                scenario.recreate()
                file.delete()
                compose.onNodeWithContentDescription("Назад").performClick()
                compose.onNodeWithText("Не сохранять").performClick()
                compose.waitUntil(10_000) { !CardImageStore(context).resolve(cancelledPath).exists() }
                assertFalse(db.cardDao().allCards().any { it.title == "$prefix cancel" })
                file.writeBytes(bytes)
                scenario.onActivity { it.startActivity(incoming("$prefix saved", false)) }
                awaitEditor("$prefix saved")
                compose.waitUntil(15_000) { model.sharedInlineImage.value?.second != cancelledPath && !model.state.value.saving }
                val savedPath = model.sharedInlineImage.value!!.second
                assertNull(model.state.value.draftImagePath)
                compose.waitUntil(10_000) { compose.onAllNodes(hasTestTagPrefix("inline-image-")).fetchSemanticsNodes().size == 1 }
                imported += savedPath
                file.delete()
                compose.onNodeWithTag("editor-save").performClick()
                compose.waitUntil(15_000) { runBlocking { db.cardDao().allCards().any { it.title == "$prefix saved" } } }
                compose.waitUntil(10_000) { !model.state.value.saving && compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
                val saved = db.cardDao().allCards().single { it.title == "$prefix saved" }
                cardId = saved.id
                assertNull(saved.imagePath)
                assertEquals(setOf(savedPath), RichTextCodec.decode(saved.description, saved.formatting).imagePaths())
                assertEquals("Подпись из галереи", saved.description)
            }
            // Destroy/relaunch the Activity and ViewModel; source URI is already unreadable.
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CARD_ID, cardId!!).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                try {
                    compose.waitUntil(15_000) { compose.onAllNodesWithTag("share-note").fetchSemanticsNodes().isNotEmpty() }
                } catch (failure: Throwable) {
                    compose.onRoot(useUnmergedTree = true).printToLog("ShareDebug")
                    throw failure
                }
                val before = db.cardDao().findById(cardId!!)!!
                val inline = RichTextCodec.decode(before.description, before.formatting).imagePaths().single()
                CardImageStore(context).load(inline, 100).recycle()
                compose.onAllNodes(hasTestTagPrefix("inline-image-")).assertCountEquals(1)
                compose.onNodeWithContentDescription("Поделиться").performClick()
                compose.waitUntil(10_000) { chooser.get() != null }
                @Suppress("DEPRECATION") val sent = chooser.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, sent.action)
                assertEquals("image/jpeg", sent.type)
                @Suppress("DEPRECATION") val attached = sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                outboundUri = attached
                assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(attached, sent.clipData!!.getItemAt(0).uri)
                assertEquals("$prefix saved\n\nПодпись из галереи", sent.getStringExtra(Intent.EXTRA_TEXT))
                assertEquals(before, db.cardDao().findById(cardId!!))
                blockChooser = false
                chooser.set(null)
                compose.onNodeWithContentDescription("Поделиться").performClick()
                nativeChooserAndBack("share-image-chooser.png")
                @Suppress("DEPRECATION") val nativeSend = chooser.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION") val nativeAttachment = nativeSend.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                nativeUri = nativeAttachment
                assertEquals(before, db.cardDao().findById(cardId!!))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            db.cardDao().allCards().filter { it.title.startsWith(prefix) }.forEach { db.cardDao().delete(it) }
            imported.forEach { CardImageStore(context).delete(it) }
            outboundUri?.lastPathSegment?.let { File(context.cacheDir, "shared-notes/$it").delete() }
            nativeUri?.lastPathSegment?.let { File(context.cacheDir, "shared-notes/$it").delete() }
            file.delete()
        }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }

    @Test fun youtubeShareCreatesMultipleVideoBlocksKeepsOriginalUrlsAndSurvivesRecreationAndReopen() = runBlocking<Unit> {
        emulatorOnly()
        val db = OnTopDatabase.getInstance(context)
        val title = "YouTube share ${System.nanoTime()}"
        val urls = listOf("https://youtu.be/M7lc1UVf-VE?si=original&t=12s", "https://youtube.com/live/dQw4w9WgXcQ")
        val text = "До 🧠\n${urls[0]}\nМежду\n${urls[1]}\nПосле"
        try {
            ActivityScenario.launch<MainActivity>(send(title, text)).use { scenario ->
                awaitEditor(title)
                compose.onAllNodesWithContentDescription("Действия с видео").assertCountEquals(2)
                scenario.recreate()
                awaitEditor(title)
                compose.onAllNodesWithContentDescription("Действия с видео").assertCountEquals(2)
                compose.onNodeWithTag("editor-save").performClick()
                compose.waitUntil(15_000) { runBlocking { db.cardDao().allCards().any { it.title == title } } }
                val saved = db.cardDao().allCards().single { it.title == title }
                val rich = RichTextCodec.decode(saved.description, saved.formatting)
                assertEquals(urls, rich.media.map { it.video!!.source })
                assertEquals("До 🧠\n\nМежду\n\nПосле", rich.text)
                assertNull(saved.imagePath)
                compose.onNodeWithTag("card-board").performScrollToNode(hasTestTag("card-${saved.id}"))
                compose.onNodeWithTag("card-${saved.id}").performClick()
                compose.onNodeWithText("Редактировать").performClick()
                compose.onAllNodesWithContentDescription("Действия с видео").assertCountEquals(2)
                compose.onNodeWithContentDescription("Назад").performClick()
            }
        } finally { db.cardDao().allCards().filter { it.title == title }.forEach { db.cardDao().delete(it) } }
    }
}
