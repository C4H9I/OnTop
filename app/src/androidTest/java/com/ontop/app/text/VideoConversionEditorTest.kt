package com.ontop.app.text

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.CardEditorScreen
import com.ontop.app.data.CardEntity
import com.ontop.app.data.OnTopDatabase
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoConversionEditorTest {
    @get:Rule val compose = createComposeRule()
    private val first = "https://youtu.be/M7lc1UVf-VE?t=12s&si=original"
    private val second = "https://youtube.com/live/dQw4w9WgXcQ"
    private val offline = object : YouTubePreviewLoader {
        override suspend fun thumbnail(id: String): android.graphics.Bitmap? = null
        override suspend fun title(id: String): String? = null
    }

    @Test fun existingLinksConvertBothWaysRestoreDraftAndPersistMultipleVideosWithExactText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, OnTopDatabase::class.java).build()
        val text = "До 🧠\n$first\nМежду\n$second\nПосле"
        val card = CardEntity(1, "Видео и текст", text, createdAt = 1, updatedAt = 2, sortOrder = 0)
        var saved: CardEntity? = null
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { CompositionLocalProvider(LocalYouTubePreviewLoader provides offline) {
                OnTopTheme { CardEditorScreen(card, false, null, null, {}, {}, {}, {}, { _, _, _ -> }, {},
                    onSaveFormatted = { title, body, size, formatting -> saved = card.copy(title = title,
                        description = body, size = size, formatting = formatting) }) }
            } }
            compose.onAllNodesWithText("Преобразовать в видео").assertCountEquals(2)
            compose.onAllNodesWithText("Преобразовать в видео").onFirst().performScrollTo().performClick()
            compose.onNodeWithContentDescription("Действия с видео").performScrollTo().performClick()
            compose.onNodeWithText("Преобразовать в ссылку").performClick()
            compose.onNodeWithTag("editor-description").assertTextEquals(text)
            compose.onAllNodesWithText("Преобразовать в видео").onFirst().performScrollTo().performClick()
            compose.onNodeWithText("Преобразовать в видео").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Действия с видео").fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithContentDescription("Действия с видео").assertCountEquals(2)
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            compose.onAllNodesWithContentDescription("Действия с видео").assertCountEquals(2)
            compose.onAllNodesWithText("Обложка недоступна · проверьте интернет").assertCountEquals(2)
            compose.onNodeWithTag("editor-save").performClick()
            db.cardDao().insert(saved!!)
            val reopened = db.cardDao().findById(1)!!
            val rich = RichTextCodec.decode(reopened.description, reopened.formatting)
            assertEquals(listOf(first, second), rich.media.map { it.video!!.source })
            var restored = rich
            rich.media.forEach { restored = restored.videoToLink(it.id) }
            assertEquals(text, restored.text)
            assertNull(reopened.imagePath)
        } finally { db.close() }
    }
}
