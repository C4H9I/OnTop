package com.ontop.app

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AppVersionLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    @Test fun menuShowsInstalledVersionCopiesAllFieldsAndReopenPreservesBuildIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = AppVersionInfo.load(context)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(installed.versionName, info.versionName)
        assertEquals(PackageInfoCompat.getLongVersionCode(installed), info.versionCode)
        val json = context.assets.open("ontop_build.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(json.getString("buildId"), info.buildId)
        assertEquals(Instant.parse(json.getString("builtAt")), info.builtAt)
        UUID.fromString(info.buildId)
        assertEquals("Debug", info.typeLabel)
        fun openAbout() {
            compose.onNodeWithContentDescription("Меню приложения").performClick()
            compose.onNodeWithText("О приложении").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("versionName: ${info.versionName}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("versionCode: ${info.versionCode}").assertExists()
            compose.onNodeWithText("Идентификатор сборки: ${info.buildId}").assertExists()
            compose.onNodeWithText("Дата и время сборки: ${info.buildDate}").assertExists()
            compose.onNodeWithText("Тип сборки: Debug").assertExists()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("dev-build").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("DEV · ${info.shortId}").assertIsDisplayed()
        openAbout()
        compose.onNodeWithText("Скопировать информацию о версии").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(info.clipboardText(), context.getSystemService(ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("Закрыть").performClick()
        compose.activityRule.scenario.recreate()
        openAbout()
        assertEquals(info, AppVersionInfo.load(context))
    }
}
