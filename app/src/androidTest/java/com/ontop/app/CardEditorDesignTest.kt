package com.ontop.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardEntity
import com.ontop.app.data.CardSize
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.CardImageStore
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.runBlocking
import java.io.File

@RunWith(Parameterized::class)
class CardEditorDesignTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private lateinit var back: OnBackPressedDispatcher
    private fun settings() {
        if (compose.onAllNodesWithTag("editor-settings-compact").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("editor-settings-compact").performClick()
        }
    }
    private fun display() { settings(); compose.onNodeWithText("Отображение").performClick() }
    private fun done() { compose.onNodeWithText("Готово").performClick() }
    private fun other() { settings(); compose.onNodeWithText("Другие настройки").performClick() }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}dp fontScale={1}")
        fun sizes() = listOf(arrayOf<Any>(320, 1f), arrayOf<Any>(280, 2f))
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            OnTopTheme(darkTheme = false) { Box(Modifier.width(width.dp).fillMaxHeight()) { content() } }
        }
    }

    @Test fun creationFocusValidationDiscardAndRestoration() {
        var closed = 0
        var saved = ""
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Frame {
                CardEditorScreen(null, false, null, null, {}, {}, {}, { closed++ },
                    { title, _, _ -> saved = title }, {})
            }
        }
        compose.onNodeWithTag("editor-title").assertIsFocused()
        compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
        compose.onNodeWithText("Отменить изменения?").assertDoesNotExist()
        compose.onNodeWithTag("editor-title").performTextInput("   ")
        compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("editor-title").performTextReplacement("Важная мысль")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("editor-title").assertTextContains("Важная мысль")
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val file = File(output, "design-review/note-editor-${width}dp-${fontScale}x.png")
            file.parentFile!!.mkdirs()
            val bitmap = compose.onNodeWithTag("card-editor").captureToImage().asAndroidBitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        compose.onNodeWithText("Продолжить редактирование").performClick()
        compose.onNodeWithTag("editor-title").assertTextContains("Важная мысль")
        compose.onNodeWithTag("editor-save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Важная мысль", saved) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Не сохранять").performClick()
        compose.runOnIdle { assertEquals(2, closed) }
    }

    @Test fun editingPrefillImageChangesErrorAndDeleteConfirmation() {
        val card = CardEntity(id = 9, title = "Исходная фраза", description = "Описание ".repeat(30),
            size = CardSize.WIDE, createdAt = 1, updatedAt = 1, sortOrder = 0,
            imagePath = "images/00000000-0000-0000-0000-000000000000.jpg")
        var image by mutableStateOf(card.imagePath)
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var closed = 0
        var deleted = 0
        var savedSize: CardSize? = null
        compose.setContent {
            Frame {
                CardEditorScreen(card, saving, error, image, {}, { image = null }, {}, { closed++ },
                    { _, _, size -> savedSize = size; saving = true }, { deleted++ })
            }
        }
        compose.onNodeWithTag("editor-title").assertTextContains(card.title)
        compose.onNodeWithTag("editor-description").assertTextContains(card.description)
        display()
        compose.onNodeWithText("Широкая").performScrollTo().assertIsSelected()
        done()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
        compose.onNodeWithText("Отменить изменения?").assertDoesNotExist()
        display()
        compose.onNodeWithText("Удалить изображение").performScrollTo().performClick()
        done()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        compose.onNodeWithText("Продолжить редактирование").performClick()
        // Reverting all fields, including the image, removes the confirmation.
        compose.runOnIdle { image = card.imagePath }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Отменить изменения?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, closed) }
        display()
        compose.onNodeWithText("Обычная").performScrollTo().performClick()
        done()
        compose.onNodeWithTag("editor-save").performClick()
        compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Назад").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(CardSize.NORMAL, savedSize); saving = false; error = "Ошибка сохранения" }
        compose.onNodeWithText("Ошибка сохранения").assertIsDisplayed()
        compose.onNodeWithTag("editor-title").assertTextContains(card.title)
        other()
        compose.onNodeWithText("Удалить карточку").performScrollTo().performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        other()
        compose.onNodeWithText("Удалить карточку").performClick()
        compose.onNodeWithText("Удалить", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun privateImagePreviewReplacementAndRemoval() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CardImageStore(context)
        val source = File(context.cacheDir, "editor-preview-${System.nanoTime()}.jpg")
        val paths = mutableListOf<String>()
        try {
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(155, 170, 139))
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            val first = store.import(Uri.fromFile(source)).also { paths.add(it) }
            val replacement = store.import(Uri.fromFile(source)).also { paths.add(it) }
            val card = CardEntity(id = 1, title = "Береги внимание", createdAt = 1, updatedAt = 1,
                sortOrder = 0, imagePath = first)
            var image by mutableStateOf<String?>(first)
            compose.setContent {
                Frame { CardEditorScreen(card, false, null, image, {}, { image = null }, {}, {}, { _, _, _ -> }, {}) }
            }
            display()
            compose.onNodeWithTag("editor-image").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { image = replacement }
            compose.onNodeWithText("Заменить изображение").performScrollTo().assertIsDisplayed()
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            if (output != null) {
                val file = File(output, "design-review/editor-preview-${width}dp-${fontScale}x.png")
                file.parentFile!!.mkdirs()
                val screenshot = compose.onNodeWithTag("card-editor").captureToImage().asAndroidBitmap()
                file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            compose.onNodeWithText("Удалить изображение").performScrollTo().performClick()
            compose.onNodeWithText("Выбрать изображение").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("editor-image").assertDoesNotExist()
            done()
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        } finally {
            for (path in paths) store.delete(path)
            source.delete()
        }
    }
}
