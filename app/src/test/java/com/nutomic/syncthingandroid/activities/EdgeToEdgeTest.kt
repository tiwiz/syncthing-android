package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Why `enableEdgeToEdge()` is called by the Compose activities and not by ThemedAppCompatActivity
 * (docs/TECH-DEBT.md): on the light theme the View screens keep a coloured status bar with light
 * icons, while the Compose screens draw behind a transparent one with dark icons. One call in the
 * base class would put dark icons on the View screens' coloured bar.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    // Android 14: from Android 15 the system makes the bars transparent whatever the app does.
    sdk = [34],
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class EdgeToEdgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        StoppedSyncthingService.install()
    }

    private data class StatusBar(val color: Int, val darkIcons: Boolean)

    private fun statusBarOf(activity: Class<out Activity>): StatusBar {
        lateinit var bar: StatusBar
        ActivityScenario.launch<Activity>(Intent(context, activity)).use { scenario ->
            scenario.onActivity {
                val window = it.window
                bar =
                    StatusBar(
                        // The colour is what the app sets below Android 15.
                        @Suppress("DEPRECATION") window.statusBarColor,
                        WindowInsetsControllerCompat(window, window.decorView)
                            .isAppearanceLightStatusBars,
                    )
            }
        }
        return bar
    }

    @Test
    fun `a View screen keeps a coloured status bar with light icons`() {
        val bar = statusBarOf(MainActivity::class.java)

        assertNotEquals(Color.TRANSPARENT, bar.color)
        assertFalse(bar.darkIcons)
    }

    @Test
    fun `a Compose screen draws behind a transparent status bar with dark icons`() {
        val bar = statusBarOf(SettingsActivity::class.java)

        assertEquals(Color.TRANSPARENT, bar.color)
        assertTrue(bar.darkIcons)
    }
}
