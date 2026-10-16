package com.nutomic.syncthingandroid.screenshots

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import java.util.TimeZone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The View-based screens with Syncthing running: the service is ACTIVE and its REST API answers
 * with the captured fixtures through [FakeSyncthing]. Light and dark theme.
 *
 * The service was never started, so the status tab says its RunConditionMonitor is missing. Times
 * are shown in UTC so the screenshots do not depend on the machine's time zone.
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
class RunningScreenshotTest(private val screen: Screen, private val dark: Boolean) {

    @get:Rule val compose = createEmptyComposeRule()

    enum class Screen(
        val activity: Class<out Activity>,
        val tab: Int? = null,
        val extras: Intent.() -> Unit = {},
        val locale: String? = null,
    ) {
        MAIN_FOLDERS(MainActivity::class.java, tab = 0),
        MAIN_DEVICES(MainActivity::class.java, tab = 1),
        MAIN_STATUS(MainActivity::class.java, tab = 2),
        /** Italian has the longest force start/stop labels. */
        MAIN_STATUS_ITALIAN(MainActivity::class.java, tab = 2, locale = "it-rIT"),
        FOLDER_EDIT(
            FolderActivity::class.java,
            extras = {
                putExtra(FolderActivity.EXTRA_IS_CREATE, false)
                putExtra(FolderActivity.EXTRA_FOLDER_ID, "cam-photos")
            },
        ),
        DEVICE_EDIT(
            DeviceActivity::class.java,
            extras = {
                putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                putExtra(DeviceActivity.EXTRA_DEVICE_ID, REMOTE_DEVICE_ID)
            },
        ),
        SETTINGS_ROOT(SettingsActivity::class.java, extras = { settingsRoute("Root") }),
        SETTINGS_SYNCTHING_OPTIONS(
            SettingsActivity::class.java,
            extras = { settingsRoute("SyncthingOptions") },
        ),
        SETTINGS_ABOUT(SettingsActivity::class.java, extras = { settingsRoute("About") }),
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var syncthing: FakeSyncthing

    private val savedTimeZone = TimeZone.getDefault()

    @After
    fun tearDown() {
        syncthing.close()
        TimeZone.setDefault(savedTimeZone)
    }

    /**
     * Lets the screen's work finish: with a compose rule, Compose recomposes on the rule's clock,
     * so it has to catch up after each round of the main looper.
     */
    private fun settleAll() {
        repeat(SETTLE_ROUNDS) {
            settle(rounds = 1)
            compose.waitForIdle()
        }
    }

    @Test
    fun screenshot() {
        System.setProperty("os.version", "6.1.0-android")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        screen.locale?.let { RuntimeEnvironment.setQualifiers("+$it") }
        ConfigFixture.install(context)
        syncthing = FakeSyncthing(context)
        RunningSyncthingService.install(syncthing)
        val intent = Intent(context, screen.activity).apply(screen.extras)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settleAll()
            screen.tab?.let { tab ->
                val title = context.getString(TAB_TITLES[tab])
                compose.onNode(hasText(title) and hasClickAction()).performClick()
                settleAll()
            }
            scenario.onActivity { it.window.decorView.hideTextCursors() }
            settleAll()
            scenario.onActivity {
                it.window.decorView.captureRoboImage(
                    screenshotPath("running", screen.name.lowercase(), dark)
                )
            }
        }
    }

    companion object {
        private const val SETTLE_ROUNDS = 20

        /** The main screen's tab titles, by index. */
        private val TAB_TITLES =
            listOf(
                R.string.folders_fragment_title,
                R.string.devices_fragment_title,
                R.string.status_fragment_title,
            )

        private fun Intent.settingsRoute(route: String) {
            putExtra(SettingsActivity.EXTRA_START_DESTINATION, route)
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun parameters(): List<Array<Any>> =
            Screen.entries.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }
}
