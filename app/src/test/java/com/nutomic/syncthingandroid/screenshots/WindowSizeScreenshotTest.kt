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

/**
 * The main, folder and device screens at each window size class the adaptive layouts target
 * (docs/plans/M3-EXPRESSIVE.md), in English and, on the smallest and largest windows, Italian.
 * Light theme only; captured at mdpi so wide windows stay small files.
 */
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
class WindowSizeScreenshotTest(
    private val screen: RunningScreen,
    private val window: Window,
    private val locale: Locale,
) {

    /** Robolectric qualifiers for each size class, in the order Robolectric expects them. */
    enum class Window(val qualifiers: String) {
        /** A phone. */
        COMPACT("w411dp-h891dp-port"),
        /** A small tablet in portrait. */
        MEDIUM("w700dp-h960dp-port"),
        /** An unfolded foldable. */
        EXPANDED("w840dp-h900dp-port"),
        /** A tablet in landscape. */
        LARGE("w1280dp-h800dp-land"),
    }

    enum class Locale(val qualifiers: String) {
        EN("en-rUS"),
        IT("it-rIT"),
    }

    @get:Rule val compose = createEmptyComposeRule()

    private val recorder = RunningScreenRecorder(compose)

    @After fun tearDown() = recorder.close()

    @Test
    fun screenshot() {
        RuntimeEnvironment.setQualifiers("${locale.qualifiers}-${window.qualifiers}-mdpi")
        val name = "${screen.name}_${window.name}_${locale.name}".lowercase()
        recorder.record(screen, screenshotPath("windows", name))
    }

    companion object {
        private val SCREENS =
            listOf(
                RunningScreen.MAIN_FOLDERS,
                RunningScreen.MAIN_DEVICES,
                RunningScreen.MAIN_STATUS,
                RunningScreen.FOLDER_EDIT,
                RunningScreen.DEVICE_EDIT,
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1} {2}")
        fun parameters(): List<Array<Any>> = SCREENS.flatMap { screen ->
            Window.entries.map { arrayOf<Any>(screen, it, Locale.EN) } +
                listOf(Window.COMPACT, Window.LARGE).map { arrayOf<Any>(screen, it, Locale.IT) }
        }
    }
}
