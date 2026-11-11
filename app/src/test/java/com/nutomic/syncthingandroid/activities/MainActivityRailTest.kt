package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The main screen with a navigation rail: the add button sits at the top of the rail, and the
 * expanded rail of a large window lists what phones keep in the overflow menu
 * (docs/plans/M3-EXPRESSIVE.md, decision 5). Driven through [MainScreen].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS-w1280dp-h800dp-land",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class MainActivityRailTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        LocalBroadcastManager::class.java.getDeclaredField("mInstance").apply {
            isAccessible = true
            set(null, null)
        }
        ConfigFixture.install(context)
    }

    private fun stopped(): MainScreen {
        StoppedSyncthingService.install()
        return MainScreen(ActivityScenario.launch(MainActivity::class.java), compose).also {
            it.idle()
        }
    }

    @Test
    fun `the expanded rail lists the overflow entries instead of an overflow button`() {
        stopped().use { screen ->
            assertEquals(MainScreen.MenuItem.entries.toList(), screen.railEntries())
            assertFalse(screen.hasOverflowButton())
        }
    }

    @Test
    fun `the exit question stays open when the window loses its expanded rail`() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_START_SERVICE_ON_BOOT, true)
            .commit()
        stopped().use { screen ->
            screen.chooseInRail(MainScreen.MenuItem.EXIT)
            assertFalse(screen.finishing())

            // Held upright the tablet has no room for the expanded rail: the menu moves to the
            // top bar, and the question must not go with the rail.
            RuntimeEnvironment.setQualifiers("en-rUS-w800dp-h1280dp-port")
            screen.recreate()
            assertTrue(screen.hasOverflowButton())

            screen.confirmExit()

            assertTrue(screen.finishing())
        }
    }

    @Test
    fun `rail entries do what the menu entries do`() {
        stopped().use { screen ->
            assertFalse(screen.railEntryEnabled(MainScreen.MenuItem.RECENT_CHANGES))

            screen.chooseInRail(MainScreen.MenuItem.EXIT)

            assertTrue(screen.finishing())
        }
    }

    @Test
    fun `the menu key does not leave an invisible menu that swallows back`() {
        stopped().use { screen ->
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            assertEquals("Edit Folder", screen.detailTitle())

            screen.pressMenuKey()
            screen.back()

            assertNull(screen.detailTitle())
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w900dp-h900dp-port")
    fun `a collapsed rail keeps the overflow button`() {
        stopped().use { screen ->
            assertTrue(screen.hasOverflowButton())
            assertEquals(emptyList<MainScreen.MenuItem>(), screen.railEntries())
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w900dp-h900dp-port")
    fun `the add button sits in the rail on every tab`() {
        stopped().use { screen ->
            for (tab in MainScreen.Tab.entries) {
                screen.showTab(tab)
                assertTrue(tab.name, screen.addInRail())
                val folder = checkNotNull(screen.run(MainScreen.Action.ADD_FOLDER))
                assertEquals(tab.name, FolderActivity::class.java.name, folder.component?.className)
            }
        }
    }

    @Test
    fun `the detail pane's share button shows that device's ID`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.DEVICES)
            screen.open(MainScreen.Tab.DEVICES, "laptop")
            screen.shareDeviceIdInDetail()

            assertTrue(screen.deviceIdDialogShows(REMOTE_DEVICE_ID))
        }
    }

    @Test
    fun `the expanded rail has the add button too`() {
        stopped().use { screen ->
            assertTrue(screen.addInRail())
            val device = checkNotNull(screen.run(MainScreen.Action.ADD_DEVICE))
            assertEquals(DeviceActivity::class.java.name, device.component?.className)
        }
    }
}
