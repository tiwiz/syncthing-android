package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One title, not two: with a rail the top bar names the tab instead of the app, and the list loses
 * its own headline; the expanded rail names the app. Phones keep the app name in the top bar and
 * the large headline above the list. Driven through [MainScreen].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS-w900dp-h900dp-port",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class MainActivityTitleTest {

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
    fun `with a rail the top bar names the tab and the list has no headline`() {
        stopped().use { screen ->
            for (tab in MainScreen.Tab.entries) {
                screen.showTab(tab)
                assertEquals(tab.title, screen.topBarTitle())
                assertFalse(tab.name, screen.hasListTitle())
            }
            assertFalse(screen.appNameInRail())
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w1280dp-h800dp-land")
    fun `the expanded rail names the app`() {
        stopped().use { screen ->
            assertEquals("Folders", screen.topBarTitle())
            assertTrue(screen.appNameInRail())
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-port")
    fun `a phone keeps the app name on top and the headline above the list`() {
        stopped().use { screen ->
            for (tab in MainScreen.Tab.entries) {
                screen.showTab(tab)
                assertEquals("Expressive Syncthing", screen.topBarTitle())
                assertTrue(tab.name, screen.hasListTitle())
            }
        }
    }
}
