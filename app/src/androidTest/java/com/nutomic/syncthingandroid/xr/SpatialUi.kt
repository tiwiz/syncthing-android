package com.nutomic.syncthingandroid.xr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

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

    /** The node showing [text], waiting for it up to [timeoutMillis]. */
    fun waitFor(text: String, timeoutMillis: Long = TIMEOUT_MILLIS): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            nodeWith(text)?.let {
                return it
            }
            SystemClock.sleep(STEP_MILLIS)
        }
        fail("Nothing shows \"$text\" after $timeoutMillis ms")
        error("unreachable")
    }

    /** Waits until nothing shows [text]. */
    fun waitGone(text: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodeWith(text) == null) return
            SystemClock.sleep(STEP_MILLIS)
        }
        fail("\"$text\" still shows after $timeoutMillis ms")
    }

    fun nodeWith(text: String): AccessibilityNodeInfo? =
        automation.windows.firstNotNullOfOrNull { window -> window.root?.let { find(it, text) } }

    /** Clicks [text], or the nearest clickable node around it. */
    fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isClickable) node = node.parent
        if (node == null) fail("Nothing clickable around \"$text\"")
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            fail("The click on \"$text\" was refused")
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
        const val TIMEOUT_MILLIS = 30_000L
        const val STEP_MILLIS = 250L
    }
}
