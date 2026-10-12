package com.nutomic.syncthingandroid.screenshots

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every settings screen, opened directly through its route, in light and dark theme. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    qualifiers = SCREENSHOT_QUALIFIERS,
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    // The About screen shells out through Util; instrument it so the shadow applies.
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SettingsScreenshotTest(private val route: String, private val dark: Boolean) {

    @Test
    fun screenshot() {
        System.setProperty("os.version", "6.1.0-android")
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        ConfigFixture.install(ApplicationProvider.getApplicationContext())
        StoppedSyncthingService.install()
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_START_DESTINATION, route)
        ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
            settle()
            scenario.onActivity {
                it.window.decorView.captureRoboImage(screenshotPath("settings", route, dark))
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun parameters(): List<Array<Any>> =
            listOf(
                    "Root",
                    "RunConditions",
                    "UserInterface",
                    "Behavior",
                    "SyncthingOptions",
                    "CustomCertificate",
                    "ImportExport",
                    "Troubleshooting",
                    "Experimental",
                    "About",
                    "Licenses",
                )
                .flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }
}
