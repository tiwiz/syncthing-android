package com.nutomic.syncthingandroid.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.recentchanges.RecentChangesActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The main screen: the folder, device and status tabs, the top bar's status light and overall
 * progress, the drawer, leaving the app and the usage reporting question; with Syncthing stopped
 * and running. The screen is driven through [MainScreen].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class MainActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var syncthing: FakeSyncthing? = null
    private lateinit var service: SyncthingService

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // LocalBroadcastManager is a process-wide singleton that would keep an earlier test's
        // context.
        LocalBroadcastManager::class.java.getDeclaredField("mInstance").apply {
            isAccessible = true
            set(null, null)
        }
        ConfigFixture.install(context)
    }

    @After
    fun tearDown() {
        syncthing?.close()
    }

    private fun stopped(intent: Intent = Intent(context, MainActivity::class.java)): MainScreen {
        service = StoppedSyncthingService.install()
        return MainScreen(ActivityScenario.launch(intent), compose).also { it.idle() }
    }

    /** With [usageReport], Syncthing answers the usage report, so the question can be asked. */
    private fun running(usageReport: Boolean = false): MainScreen {
        val fake = FakeSyncthing(context).also { syncthing = it }
        if (usageReport) fake.respond("GET", "/rest/svc/report", """{"uniqueID":"fake"}""")
        service = RunningSyncthingService.install(fake)
        return MainScreen(ActivityScenario.launch(MainActivity::class.java), compose).also {
            it.idle()
        }
    }

    private fun receive(action: String): MutableList<Intent> {
        val received = mutableListOf<Intent>()
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        received += intent
                    }
                },
                IntentFilter(action),
            )
        return received
    }

    @Test
    fun `with Syncthing stopped the tabs list the configured folders and devices`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            assertEquals(listOf("Camera"), screen.folders())
            assertNull(screen.folderState("Camera"))

            screen.showTab(MainScreen.Tab.DEVICES)
            assertEquals(listOf("laptop"), screen.devices())
            assertEquals("Disconnected", screen.deviceStatus("laptop"))
        }
    }

    @Test
    fun `a folder or device opens for editing`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            val folder = checkNotNull(screen.open(MainScreen.Tab.FOLDERS, "Camera"))
            assertEquals(FolderActivity::class.java.name, folder.component?.className)
            assertFalse(folder.getBooleanExtra(FolderActivity.EXTRA_IS_CREATE, true))
            assertEquals("cam-photos", folder.getStringExtra(FolderActivity.EXTRA_FOLDER_ID))

            screen.showTab(MainScreen.Tab.DEVICES)
            val device = checkNotNull(screen.open(MainScreen.Tab.DEVICES, "laptop"))
            assertEquals(DeviceActivity::class.java.name, device.component?.className)
            assertFalse(device.getBooleanExtra(DeviceActivity.EXTRA_IS_CREATE, true))
            assertEquals(REMOTE_DEVICE_ID, device.getStringExtra(DeviceActivity.EXTRA_DEVICE_ID))
        }
    }

    @Test
    fun `the top bar actions add folders and devices and open the settings`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            val folder = checkNotNull(screen.run(MainScreen.Action.ADD_FOLDER))
            assertEquals(FolderActivity::class.java.name, folder.component?.className)
            assertTrue(folder.getBooleanExtra(FolderActivity.EXTRA_IS_CREATE, false))

            screen.showTab(MainScreen.Tab.DEVICES)
            val device = checkNotNull(screen.run(MainScreen.Action.ADD_DEVICE))
            assertEquals(DeviceActivity::class.java.name, device.component?.className)
            assertTrue(device.getBooleanExtra(DeviceActivity.EXTRA_IS_CREATE, false))

            screen.showTab(MainScreen.Tab.STATUS)
            val settings = checkNotNull(screen.run(MainScreen.Action.SETTINGS))
            assertEquals(SettingsActivity::class.java.name, settings.component?.className)
        }
    }

    @Test
    fun `rescanning needs Syncthing running`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            assertNull(screen.run(MainScreen.Action.RESCAN_ALL))
        }
    }

    @Test
    fun `with Syncthing stopped the status tab says so and why`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.STATUS)

            val lines = screen.statusLines()
            assertEquals("Syncthing is not running.", lines[0])
            assertTrue(lines[1], lines[1].startsWith("Reason:\n- "))
            assertEquals(2, lines.size)
            assertEquals(MainScreen.Light.DISABLED, screen.light())
            assertNull(screen.totalProgress())
        }
    }

    @Test
    fun `forcing Syncthing on or off is remembered and re-evaluates the run conditions`() {
        val decisions = receive(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION)

        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.STATUS)
            assertEquals(Constants.BTNSTATE_NO_FORCE_START_STOP, screen.forceStartStop())

            screen.chooseForceStartStop(Constants.BTNSTATE_FORCE_STOP)

            assertEquals(
                Constants.BTNSTATE_FORCE_STOP,
                prefs.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, -1),
            )
            assertEquals(1, decisions.size)

            // A change elsewhere, e.g. from the quick settings tile, shows up here.
            prefs
                .edit()
                .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_START)
                .commit()
            screen.idle()
            assertEquals(Constants.BTNSTATE_FORCE_START, screen.forceStartStop())
        }
    }

    @Test
    fun `exit in the menu stops Syncthing and leaves, asking first when running as a service`() {
        stopped().use { screen ->
            screen.openMenu()
            screen.choose(MainScreen.MenuItem.EXIT)

            assertTrue(screen.finishing())
        }

        prefs.edit().putBoolean(Constants.PREF_START_SERVICE_ON_BOOT, true).commit()
        stopped().use { screen ->
            screen.openMenu()
            screen.choose(MainScreen.MenuItem.EXIT)
            assertFalse(screen.finishing())

            screen.confirmExit()

            assertTrue(screen.finishing())
        }
    }

    @Test
    fun `the add button offers new folders and devices on both lists`() {
        stopped().use { screen ->
            for (tab in listOf(MainScreen.Tab.FOLDERS, MainScreen.Tab.DEVICES)) {
                screen.showTab(tab)
                val folder = checkNotNull(screen.run(MainScreen.Action.ADD_FOLDER))
                assertEquals(tab.name, FolderActivity::class.java.name, folder.component?.className)
                val device = checkNotNull(screen.run(MainScreen.Action.ADD_DEVICE))
                assertEquals(tab.name, DeviceActivity::class.java.name, device.component?.className)
            }
        }
    }

    @Test
    fun `exiting from the notification leaves the app`() {
        stopped(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_EXIT))
            .use { screen -> assertTrue(screen.finishing()) }
    }

    @Test
    fun `back closes the menu first, then keeps the screen in the background`() {
        stopped().use { screen ->
            screen.openMenu()
            assertTrue(screen.menuOpen())

            screen.back()
            assertFalse(screen.menuOpen())

            screen.back()
            assertFalse(screen.finishing())
        }
    }

    @Test
    fun `the settings button is on every tab`() {
        stopped().use { screen ->
            for (tab in MainScreen.Tab.entries) {
                screen.showTab(tab)
                val settings = checkNotNull(screen.run(MainScreen.Action.SETTINGS))
                assertEquals(
                    tab.name,
                    SettingsActivity::class.java.name,
                    settings.component?.className,
                )
            }
        }
    }

    @Test
    fun `the menu offers import and export, and needs Syncthing running for the rest`() {
        stopped().use { screen ->
            screen.openMenu()
            assertTrue(screen.menuItemEnabled(MainScreen.MenuItem.DEVICE_ID))
            assertFalse(screen.menuItemEnabled(MainScreen.MenuItem.RECENT_CHANGES))
            assertFalse(screen.menuItemEnabled(MainScreen.MenuItem.WEB_GUI))
            assertFalse(screen.menuItemEnabled(MainScreen.MenuItem.RESTART))
            assertTrue(screen.menuItemEnabled(MainScreen.MenuItem.EXIT))

            val backup = checkNotNull(screen.choose(MainScreen.MenuItem.BACKUP))
            assertEquals(SettingsActivity::class.java.name, backup.component?.className)
            assertEquals(
                "ImportExport",
                backup.getStringExtra(SettingsActivity.EXTRA_START_DESTINATION),
            )
            assertFalse(screen.menuOpen())
        }
    }

    @Test
    fun `with Syncthing running the menu opens recent changes and the web GUI`() {
        running().use { screen ->
            screen.openMenu()
            val recent = checkNotNull(screen.choose(MainScreen.MenuItem.RECENT_CHANGES))
            assertEquals(RecentChangesActivity::class.java.name, recent.component?.className)

            screen.openMenu()
            val webGui = checkNotNull(screen.choose(MainScreen.MenuItem.WEB_GUI))
            assertEquals(WebGuiActivity::class.java.name, webGui.component?.className)
        }
    }

    @Test
    fun `the menu key opens and closes the menu`() {
        stopped().use { screen ->
            screen.pressMenuKey()
            assertTrue(screen.menuOpen())

            screen.pressMenuKey()
            assertFalse(screen.menuOpen())
        }
    }

    @Test
    fun `a crashed Syncthing closes the screen`() {
        stopped().use { screen ->
            RunningSyncthingService.setState(service, SyncthingService.State.ERROR)

            assertTrue(screen.finishing())
        }
    }

    @Test
    fun `with Syncthing running the lists show folder and device states`() {
        running().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            awaitMain { screen.folderState("Camera") != null }
            assertNotNull(screen.folderState("Camera"))

            screen.showTab(MainScreen.Tab.DEVICES)
            // The fixture's completion leaves a few bytes for the laptop.
            assertEquals("Disconnected (3 B pending)", screen.deviceStatus("laptop"))
            assertEquals(MainScreen.Light.ACTIVE, screen.light())
        }
    }

    @Test
    fun `with Syncthing running the status tab shows its figures`() {
        running().use { screen ->
            screen.showTab(MainScreen.Tab.STATUS)
            awaitMain {
                screen.idle(millis = Constants.REST_UPDATE_INTERVAL)
                screen.statusLines().any { it.startsWith("Uptime: ") }
            }

            val lines = screen.statusLines()
            assertEquals("Syncthing is running.", lines[0])
            assertTrue(lines[1], lines[1].startsWith("Reason:\n- "))
            assertTrue(lines.toString(), lines.any { it.startsWith("RAM Usage: ") })
            assertTrue(lines.toString(), lines.any { it.startsWith("Download: ") })
            assertTrue(lines.toString(), lines.any { it.startsWith("Upload: ") })
        }
    }

    @Test
    fun `rescanning all folders asks Syncthing`() {
        running().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.run(MainScreen.Action.RESCAN_ALL)

            awaitMain { checkNotNull(syncthing).requests("/rest/db/scan", "POST").isNotEmpty() }
        }
    }

    @Test
    fun `the overall progress shows once a device is connected`() {
        running().use { screen ->
            screen.idle(millis = Constants.GUI_UPDATE_INTERVAL)
            assertNull(screen.totalProgress())

            checkNotNull(service.api).updateRemoteDeviceConnected(REMOTE_DEVICE_ID, true)
            screen.idle(millis = Constants.GUI_UPDATE_INTERVAL)

            assertEquals("100", screen.totalProgress())
        }
    }

    @Test
    fun `without a usage report nothing is asked`() {
        running().use { screen ->
            screen.idle(millis = Constants.GUI_UPDATE_INTERVAL)

            assertFalse(screen.usageReportingAsked())
        }
    }

    @Test
    fun `usage reporting is asked once Syncthing runs, and the answer is sent`() {
        running(usageReport = true).use { screen ->
            awaitMain { screen.usageReportingAsked() }

            screen.answerUsageReporting(MainScreen.YES)

            assertTrue(checkNotNull(service.api).isUsageReportingAccepted)
            awaitMain {
                checkNotNull(syncthing).requests("/rest/system/config", "POST").isNotEmpty()
            }
        }
    }

    @Test
    fun `the usage reporting question survives recreation without asking Syncthing again`() {
        running(usageReport = true).use { screen ->
            awaitMain { screen.usageReportingAsked() }
            val reportRequests = checkNotNull(syncthing).requests("/rest/svc/report").size

            screen.recreate()

            assertTrue(screen.usageReportingAsked())
            assertEquals(reportRequests, checkNotNull(syncthing).requests("/rest/svc/report").size)
        }
    }

    @Test
    fun `the usage reporting website can be opened from the question`() {
        running(usageReport = true).use { screen ->
            awaitMain { screen.usageReportingAsked() }
            screen.answerUsageReporting(MainScreen.WEBSITE)

            val opened =
                org.robolectric.Shadows.shadowOf(context as android.app.Application)
                    .nextStartedActivity
            assertEquals(WebViewActivity::class.java.name, opened.component?.className)
            assertTrue(
                opened
                    .getStringExtra(WebViewActivity.EXTRA_WEB_URL)
                    .orEmpty()
                    .startsWith("https://")
            )
        }
    }
}
