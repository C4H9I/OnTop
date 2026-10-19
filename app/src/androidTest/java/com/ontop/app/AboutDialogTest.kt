package com.ontop.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class AboutDialogTest {
    @get:Rule val compose = createComposeRule()
    private val info = AppVersionInfo("OnTop", "1.0", 1,
        "12345678-1234-1234-1234-123456789012", Instant.parse("2026-10-18T09:00:00Z"), "release")

    @Test fun releaseHeaderHasMenuAndHidesTechnicalBuildId() {
        compose.setContent { OnTopTheme { BoardHeaderContent(info, false, {}, {}) } }
        compose.onNodeWithContentDescription("Меню приложения").assertIsDisplayed()
        compose.onNodeWithTag("dev-build").assertDoesNotExist()
    }

    @Test fun aboutContentScrollsWithEnlargedFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OnTopTheme { AboutDialog(info, false, {}, {}) }
            }
        }
        compose.onNodeWithText("О приложении").assertIsDisplayed()
        compose.onNodeWithText("Скопировать информацию о версии").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Тип сборки: Release").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").assertIsDisplayed()
    }
}
