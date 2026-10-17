package com.ontop.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry

/** Require a positive IME inset, expanding Gboard's hardware-keyboard toolbar when necessary. */
internal fun awaitDockedKeyboard(compose: ComposeContentTestRule, vararg controlTags: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val originalFlags = automation.serviceInfo.flags
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    var diagnostic = ""
    var lastAttempt = 0L
    var clicks = 0
    try {
        compose.waitUntil(15_000) {
            var inset = 0
            var keyboardTop = 0
            instrumentation.runOnMainSync {
                val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                if (root != null) {
                    inset = root.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    keyboardTop = root.context.getSystemService(WindowManager::class.java)
                        .maximumWindowMetrics.bounds.bottom - inset - location[1]
                    diagnostic = "root=${root.height}, inset=$inset, keyboardTop=$keyboardTop"
                }
            }
            if (inset == 0 && clicks < 4 && SystemClock.uptimeMillis() - lastAttempt > 600) {
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo) {
                    nodes.add(node)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                val windows = automation.windows
                // Gboard's toolbar menu is a separate popup window, not TYPE_INPUT_METHOD.
                windows.forEach { window ->
                    window.root?.takeIf { window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        it.packageName?.toString() == "com.google.android.inputmethod.latin" }?.let(::visit)
                }
                val labeled = nodes.firstOrNull {
                    "show on-screen keyboard" in "${it.text} ${it.contentDescription}".lowercase()
                }
                val control = generateSequence(labeled) { it.parent }.firstOrNull { it.isClickable }
                    ?: nodes.filter { it.isClickable }.singleOrNull()
                // A late accessibility tree must not consume the limited expansion attempts.
                if (control?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) clicks++
                diagnostic += ", windows=" + windows.joinToString { "${it.type}/${it.title}" } +
                    ", nodes=" + nodes.joinToString { "${it.text}/${it.contentDescription}" }
                lastAttempt = SystemClock.uptimeMillis()
            }
            inset > 0 && controlTags.all {
                compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop + 1
            }
        }
    } catch (failure: Throwable) {
        throw AssertionError(diagnostic, failure)
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
    }
}
