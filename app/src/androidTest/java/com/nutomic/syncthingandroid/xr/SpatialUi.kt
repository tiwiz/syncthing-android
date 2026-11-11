package com.nutomic.syncthingandroid.xr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Reaches the app's spatial panels and orbiters through accessibility. On Android XR each panel and
 * orbiter is a window of its own for accessibility, while injected touches only reach the hidden
 * main window, so clicks here are accessibility actions, as a screen reader would do.
 */
class SpatialUi {

    private val automation: UiAutomation =
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }

    /** The windows showing [text], as text or content description. */
    fun windowsWith(text: String): List<AccessibilityWindowInfo> =
        automation.windows.filter { window -> window.root?.let { find(it, text) } != null }

    /** Waits until [condition] holds, failing with [what] after [timeoutMillis]. */
    fun waitUntil(what: String, timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("$what, still not after $timeoutMillis ms")
            }
            SystemClock.sleep(STEP_MILLIS)
        }
    }

    /** The node showing [text], waiting for it. */
    fun waitFor(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitUntil("Something shows \"$text\"") { nodeWith(text).also { node = it } != null }
        return checkNotNull(node)
    }

    /** Waits until nothing shows [text]. */
    fun waitGone(text: String) = waitUntil("Nothing shows \"$text\"") { nodeWith(text) == null }

    fun nodeWith(text: String): AccessibilityNodeInfo? =
        automation.windows.firstNotNullOfOrNull { window -> window.root?.let { find(it, text) } }

    /** Clicks [text], or the nearest clickable node around it. */
    fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isClickable) node = node.parent
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            throw AssertionError("No click on \"$text\": nothing clickable, or it was refused")
        }
    }

    /** The system Back, as the gesture or the controller's button sends it. */
    fun back() {
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun shell(command: String) {
        automation.executeShellCommand(command).close()
    }

    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                find(child, text)?.let {
                    return it
                }
            }
        }
        return null
    }

    private companion object {
        /**
         * Enough on a headset. The emulator can take minutes to draw a panel: pass a longer one
         * with `-Pandroid.testInstrumentationRunnerArguments.spatialTimeoutMillis=...`.
         */
        val TIMEOUT_MILLIS: Long =
            InstrumentationRegistry.getArguments().getString("spatialTimeoutMillis")?.toLongOrNull()
                ?: 30_000L
        const val STEP_MILLIS = 250L
    }
}
