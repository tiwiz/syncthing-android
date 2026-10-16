package com.nutomic.syncthingandroid.screenshots

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
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
import java.util.TimeZone
import org.robolectric.RuntimeEnvironment

/** The screens that need Syncthing running, and how to open them. */
enum class RunningScreen(
    val activity: Class<out Activity>,
    val tab: Int? = null,
    val extras: Intent.() -> Unit = {},
    val locale: String? = null,
    /** The test tag of a row to tap once the tab shows, opening it in the detail pane. */
    val select: String? = null,
) {
    MAIN_FOLDERS(MainActivity::class.java, tab = 0),
    MAIN_DEVICES(MainActivity::class.java, tab = 1),
    MAIN_STATUS(MainActivity::class.java, tab = 2),
    /** Italian has the longest force start/stop labels. */
    MAIN_STATUS_ITALIAN(MainActivity::class.java, tab = 2, locale = "it-rIT"),
    /** Large windows only: on a phone the row opens a screen of its own. */
    MAIN_FOLDERS_DETAIL(MainActivity::class.java, tab = 0, select = "folder:Camera"),
    MAIN_DEVICES_DETAIL(MainActivity::class.java, tab = 1, select = "device:laptop"),
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

private fun Intent.settingsRoute(route: String) {
    putExtra(SettingsActivity.EXTRA_START_DESTINATION, route)
}

/** The main screen's tab titles, by index. */
private val TAB_TITLES =
    listOf(
        R.string.folders_fragment_title,
        R.string.devices_fragment_title,
        R.string.status_fragment_title,
    )

private const val SETTLE_ROUNDS = 20

/**
 * Opens a [RunningScreen] with the service ACTIVE and its REST API answered by [FakeSyncthing] with
 * the captured fixtures, then captures it. The service was never started, so the status tab says
 * its RunConditionMonitor is missing. Times are shown in UTC so the screenshots do not depend on
 * the machine's time zone. Set the qualifiers (size, locale, night) before [record].
 */
internal class RunningScreenRecorder(private val compose: ComposeTestRule) : AutoCloseable {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val savedTimeZone = TimeZone.getDefault()
    private var syncthing: FakeSyncthing? = null

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

    fun record(screen: RunningScreen, path: String) {
        System.setProperty("os.version", "6.1.0-android")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        screen.locale?.let { RuntimeEnvironment.setQualifiers("+$it") }
        ConfigFixture.install(context)
        val fake = FakeSyncthing(context).also { syncthing = it }
        RunningSyncthingService.install(fake)
        val intent = Intent(context, screen.activity).apply(screen.extras)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settleAll()
            screen.tab?.let { tab ->
                val title = context.getString(TAB_TITLES[tab])
                compose.onNode(hasText(title) and hasClickAction()).performClick()
                settleAll()
            }
            screen.select?.let {
                compose.onNodeWithTag(it).performClick()
                settleAll()
            }
            scenario.onActivity { it.window.decorView.hideTextCursors() }
            settleAll()
            scenario.onActivity { it.window.decorView.captureRoboImage(path) }
        }
    }

    override fun close() {
        syncthing?.close()
        TimeZone.setDefault(savedTimeZone)
    }
}
