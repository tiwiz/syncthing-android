package com.nutomic.syncthingandroid.screenshots

import android.webkit.WebView
import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.webgui.WebGuiScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The web GUI screen when the GUI fails to load, in light and dark theme. Robolectric draws no web
 * content, and the loading spinner never stops moving, so those states are left to the instrumented
 * tests.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class WebGuiScreenshotTest(private val dark: Boolean) {

    @Test
    fun screenshot() {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        captureRoboImage(screenshotPath("webgui", "load_failed", dark)) {
            ApplicationTheme {
                WebGuiScreen(
                    loading = false,
                    error = "net::ERR_CONNECTION_REFUSED (-6)",
                    onNavigateBack = {},
                    webViewFactory = ::WebView,
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
