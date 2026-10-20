package com.ontop.app.lifecycle

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.*
import com.ontop.app.data.*
import com.ontop.app.ui.theme.OnTopTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoteLifecycleUiTest {
    @get:Rule val compose = createComposeRule()
    private fun action(label: String) {
        compose.onNodeWithContentDescription("Меню заметки").performClick()
        compose.onNodeWithText(label).performClick()
        if (label != "Удалить навсегда") compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Меню заметки").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun collection(label: String) {
        compose.onNodeWithContentDescription("Меню приложения").performClick()
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithTag("collection-card-1").performClick()
    }

    @Test fun archiveTrashRestoreAndConfirmedPermanentDeletionFromViewingMenu() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, OnTopDatabase::class.java).build()
        var model: BoardViewModel? = null
        try {
            db.cardDao().insert(CardEntity(1, "Жизненный цикл", "Текст сохраняется", createdAt = 1, updatedAt = 1, sortOrder = 0))
            db.cardCommentDao().insert(CardCommentEntity(cardId = 1, text = "История сохраняется", createdAt = 1))
            instrumentation.runOnMainSync { model = BoardViewModel(app, db) }
            compose.setContent {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                OnTopTheme { BoardScreen(model!!, owner.lifecycle) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("card-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("card-1").performClick()
            action("Архивировать")
            compose.waitUntil(10_000) { runBlocking { db.cardDao().findById(1)!!.status == CardStatus.ARCHIVED } }
            compose.onNodeWithTag("card-1").assertDoesNotExist()
            collection("Архив")
            compose.onNodeWithText("Текст сохраняется").assertExists()
            compose.onNodeWithText("История сохраняется").assertExists()
            action("Восстановить из архива")
            compose.onNodeWithText("Архив пуст").assertExists()
            compose.onNodeWithText("На доску").performClick()
            compose.onNodeWithTag("card-1").performClick()
            action("Удалить")
            collection("Корзина")
            compose.onNodeWithText("Редактировать").assertIsNotEnabled()
            action("Восстановить из корзины")
            compose.onNodeWithText("Корзина пуста").assertExists()
            compose.onNodeWithText("На доску").performClick()
            compose.onNodeWithTag("card-1").performClick()
            action("Удалить")
            collection("Корзина")
            action("Удалить навсегда")
            compose.onNodeWithText("Отмена").performClick()
            assertNotNull(db.cardDao().findById(1))
            action("Удалить навсегда")
            compose.onNodeWithText("Удалить навсегда").performClick()
            compose.waitUntil(10_000) { runBlocking { db.cardDao().findById(1) == null } }
            compose.onNodeWithText("Корзина пуста").assertExists()
            assertTrue(db.cardCommentDao().all().isEmpty())
        } finally { model?.viewModelScope?.cancel(); db.close() }
    }
}
