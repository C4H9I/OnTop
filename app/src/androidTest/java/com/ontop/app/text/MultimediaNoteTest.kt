package com.ontop.app.text

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MultimediaNoteTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun textAfterMedia() = compose.onAllNodes(SemanticsMatcher("following text") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("editor-text-") == true
    }).onLast()
    private fun action(label: String) = compose.onNodeWithContentDescription(label).performScrollTo().performClick()
    private fun capture(name: String) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val file = File(output, "design-review/$name.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun pickerImportsBlocksKeepOrderStylesAndSavedImagesAndDiscardCleansOnlyDrafts() = runBlocking<Unit> {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        val source = File(app.cacheDir, "media-fixture-${System.nanoTime()}.jpg")
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
        }
        lateinit var model: BoardViewModel
        val imported = mutableSetOf<String>()
        var pickerCalls = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                check(contract is ActivityResultContracts.PickVisualMedia)
                pickerCalls++
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, Uri.fromFile(source) as O)
            }
        }
        try {
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) { OnTopTheme { BoardScreen(model, owner.lifecycle) } }
            }
            compose.onNodeWithContentDescription("Создать карточку").performClick()
            compose.onNodeWithTag("editor-title").performTextInput("Мультимедиа")
            val body = compose.onNodeWithTag("editor-description")
            body.performTextInput("before after")
            body.performTextInputSelection(TextRange(0, 12)); action("Жирный"); action("Курсив")
            body.performTextInputSelection(TextRange(6)); action("Вставить изображение")
            compose.waitUntil(5_000) { compose.onAllNodes(inlineImages()).fetchSemanticsNodes().size == 1 }
            textAfterMedia().performScrollTo().performTextInput("AFTER")
            textAfterMedia().performTextInputSelection(TextRange(0)); action("Вставить изображение")
            compose.waitUntil(5_000) { compose.onAllNodes(inlineImages()).fetchSemanticsNodes().size == 2 }
            textAfterMedia().performScrollTo().performTextInputSelection(TextRange(0)); action("Вставить видео")
            compose.onNodeWithText("Ссылка YouTube").performTextInput("https://youtu.be/M7lc1UVf-VE?t=12s")
            compose.onNodeWithText("Вставить видео", useUnmergedTree = false).performClick()
            textAfterMedia().performScrollTo().performTextInput("tail")
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            textAfterMedia().assertTextContains("tail", substring = true)
            assertTrue(db.cardDao().observeCards().first().isEmpty())
            capture("multimedia-note-editor")
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            val saved = db.cardDao().observeCards().first().single()
            var rich = RichTextCodec.decode(saved.description, saved.formatting)
            imported.addAll(rich.imagePaths())
            assertEquals(2, pickerCalls); assertEquals(2, rich.imagePaths().size)
            assertEquals(listOf(MediaKind.IMAGE, MediaKind.IMAGE, MediaKind.YOUTUBE), rich.media.map { it.kind })
            assertEquals(12, rich.media.last().video!!.seconds)
            assertTrue(rich.text.contains("before")); assertTrue(rich.text.contains("after")); assertTrue(rich.text.contains("tail"))
            assertTrue(rich.has(RichKind.BOLD, 0, 6)); assertTrue(rich.has(RichKind.ITALIC, 0, 6))
            rich.imagePaths().forEach { assertTrue(CardImageStore(app).resolve(it).exists()) }
            compose.onNodeWithTag("card-${saved.id}").performClick()
            compose.onAllNodes(inlineImages()).onFirst().performScrollTo().performClick()
            compose.onNodeWithTag("full-note-image").assertIsDisplayed()
            compose.onNodeWithText("Закрыть изображение").performClick()
            capture("multimedia-note-view")
            compose.onNodeWithText("Редактировать").performClick()
            compose.onAllNodes(inlineImages()).onFirst().performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("Удалить изображение из текста").performClick()
            compose.onNodeWithTag("editor-save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            rich = RichTextCodec.decode(db.cardDao().findById(saved.id)!!.description, db.cardDao().findById(saved.id)!!.formatting)
            assertEquals(1, rich.imagePaths().size)
            assertEquals(saved.description, rich.text)
            assertTrue(rich.imagePaths().all { CardImageStore(app).resolve(it).exists() })
            assertFalse(CardImageStore(app).resolve((imported - rich.imagePaths()).single()).exists())
            val filesBeforeDraft = File(app.filesDir, "images").listFiles().orEmpty().map { it.name }.toSet()
            compose.onNodeWithTag("card-${saved.id}").performClick(); compose.onNodeWithText("Редактировать").performClick()
            body.performScrollTo().performTextInputSelection(TextRange(0)); action("Вставить изображение")
            compose.waitUntil(5_000) { pickerCalls == 3 && !model.state.value.saving }
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Не сохранять").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("card-editor").fetchSemanticsNodes().isEmpty() }
            assertEquals(rich, RichTextCodec.decode(db.cardDao().findById(saved.id)!!.description, db.cardDao().findById(saved.id)!!.formatting))
            assertEquals(filesBeforeDraft, File(app.filesDir, "images").listFiles().orEmpty().map { it.name }.toSet())
        } catch (failure: Throwable) { capture("multimedia-note-failure"); throw failure
        } finally {
            model.viewModelScope.cancel(); db.close(); source.delete()
            imported.forEach { CardImageStore(app).delete(it) }
        }
    }
}
