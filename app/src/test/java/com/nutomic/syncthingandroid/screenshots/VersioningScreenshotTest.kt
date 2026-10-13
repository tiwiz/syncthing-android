package com.nutomic.syncthingandroid.screenshots

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.activities.VersioningDialogActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The file versioning dialog for each versioning type, with its defaults, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class VersioningScreenshotTest(private val type: String, private val dark: Boolean) {

    @Test
    fun screenshot() {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        val intent =
            Intent(
                    ApplicationProvider.getApplicationContext(),
                    VersioningDialogActivity::class.java,
                )
                .putExtra("type", type)
        ActivityScenario.launch<VersioningDialogActivity>(intent).use { scenario ->
            settle()
            scenario.onActivity {
                it.window.decorView.hideTextCursors()
                settle(rounds = 2)
                it.window.decorView.captureRoboImage(screenshotPath("versioning", type, dark))
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun parameters(): List<Array<Any>> =
            listOf("none", "trashcan", "simple", "staggered", "external").flatMap {
                listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true))
            }
    }
}
