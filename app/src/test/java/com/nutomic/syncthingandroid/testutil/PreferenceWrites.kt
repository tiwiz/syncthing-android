package com.nutomic.syncthingandroid.testutil

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Waits until [written] holds for a preference a settings screen changed. The preference library
 * writes from a coroutine on the main looper, so the looper is run while waiting; a busy CI machine
 * can take longer than the default second.
 */
fun ComposeTestRule.awaitPreference(written: () -> Boolean) {
    waitUntil(timeoutMillis = PREFERENCE_WRITE_TIMEOUT_MILLIS) {
        shadowOf(Looper.getMainLooper()).idle()
        written()
    }
}

private const val PREFERENCE_WRITE_TIMEOUT_MILLIS = 5_000L
