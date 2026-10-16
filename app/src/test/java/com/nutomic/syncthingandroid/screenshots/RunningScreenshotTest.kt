package com.nutomic.syncthingandroid.screenshots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screens with Syncthing running ([RunningScreenRecorder]), in light and dark theme. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    qualifiers = SCREENSHOT_QUALIFIERS,
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class RunningScreenshotTest(private val screen: RunningScreen, private val dark: Boolean) {

    @get:Rule val compose = createEmptyComposeRule()

    private val recorder = RunningScreenRecorder(compose)

    @After fun tearDown() = recorder.close()

    @Test
    fun screenshot() {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        recorder.record(screen, screenshotPath("running", screen.name.lowercase(), dark))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun parameters(): List<Array<Any>> =
            RunningScreen.entries.flatMap {
                listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true))
            }
    }
}
