package com.nutomic.syncthingandroid.screenshots

/** A Pixel-like phone in English, so screenshots do not depend on the machine's locale. */
const val SCREENSHOT_QUALIFIERS = "en-rUS-w411dp-h891dp-xxhdpi"

/** Golden screenshots live in `app/src/test/screenshots/<group>/<name>[-dark].png`. */
fun screenshotPath(group: String, name: String, dark: Boolean = false): String =
    "src/test/screenshots/$group/$name${if (dark) "-dark" else ""}.png"

/**
 * Lets work started by the screen finish before capturing: runs the main looper and gives
 * background coroutines (e.g. the licenses list loading on Dispatchers.IO) time to post back.
 */
fun settle(rounds: Int = 20) {
    repeat(rounds) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(25)
    }
}

/** Hides blinking text cursors so View-based screenshots are stable. */
fun android.view.View.hideTextCursors() {
    if (this is android.widget.EditText) isCursorVisible = false
    if (this is android.view.ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).hideTextCursors()
    }
}

/**
 * Turns the system's animations off, as on the test emulator: the sync progress wave then stands
 * still. A moving wave is drawn at a different phase from run to run, so goldens would not match.
 */
class StillAnimationsRule : org.junit.rules.ExternalResource() {
    override fun before() {
        android.provider.Settings.Global.putFloat(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<
                    android.content.Context
                >()
                .contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
    }
}
