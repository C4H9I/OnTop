package com.ontop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ontop.app.data.CardCommentEntity
import com.ontop.app.data.CardEntity
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReminderDetailsDesignTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp font={1}")
        fun sizes() = listOf(arrayOf<Any>(320, 1f), arrayOf<Any>(280, 2f))
    }
    @Test fun bellStaysVisibleAndClickableAboveLongContentAndScrolledHistory() {
        var opened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OnTopTheme {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        CardDetailsContent(CardEntity(id = 1, title = "Очень важная мысль. ".repeat(30),
                            description = "Подробное описание. ".repeat(200), createdAt = 1, updatedAt = 1, sortOrder = 0),
                            (1L..80L).map { CardCommentEntity(it, 1, "Запись $it", it) }, true, false, false, null,
                            onAddComment = { _, _ -> }, onDeleteComment = { _, _ -> }, onDismiss = {}, onEdit = {},
                            onReminder = { opened++ })
                    }
                }
            }
        }
        val bell = compose.onNodeWithTag("reminder-settings")
        bell.assertIsDisplayed().performClick()
        val before = bell.fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithText("Редактировать").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("Закрыть карточку").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(before.left >= edit.right)
        assertTrue(back.right <= edit.left)
        compose.onNodeWithTag("reflection-list").performScrollToIndex(60)
        bell.assertIsDisplayed().performClick()
        assertEquals(before, bell.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(2, opened) }
    }
}
