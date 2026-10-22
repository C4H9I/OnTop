package com.ontop.app.media

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.ui.theme.OnTopTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioPermissionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun microphoneDenialIsExplainedAndCancelDoesNotInsertAnything() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denied = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
        }
        var inserted = false; var requested = false
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requested = true
                Handler(Looper.getMainLooper()).post { dispatchResult(code, false) }
            }
        }
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalContext provides denied, LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { OnTopTheme { if (open) AudioRecordingDialog({ inserted = true }, { open = false }) } }
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("recording-start") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recording-start").performClick()
        compose.onNodeWithText("Доступ к микрофону не предоставлен. Можно выбрать готовый аудиофайл.").assertIsDisplayed()
        compose.onNodeWithTag("recording-insert").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertTrue(requested); assertFalse(inserted) }
        compose.onNodeWithTag("audio-recorder").assertDoesNotExist()
    }
}
