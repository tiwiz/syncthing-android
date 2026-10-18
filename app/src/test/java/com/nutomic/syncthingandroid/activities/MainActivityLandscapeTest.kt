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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screen on a phone turned sideways: the window is wide but short, so the destinations
 * move to a rail at the side and the list title shrinks, leaving the height to the lists. Native
 * graphics, so text is measured with its real font.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    qualifiers = "en-rUS-w891dp-h411dp-land",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class MainActivityLandscapeTest {

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
    fun `the destinations sit in a rail at the start, not in a bar at the bottom`() {
        stopped().use { screen ->
            val size = screen.rootSize()
            for (tab in MainScreen.Tab.entries) {
                val bounds = screen.tabBounds(tab)
                assertTrue("$tab at $bounds", bounds.right < size.width / 4f)
                assertTrue("$tab at $bounds", bounds.bottom < size.height * 0.9f)
            }
        }
    }

    @Test
    fun `the list title is smaller than on a phone held upright`() {
        val sideways =
            stopped().use { screen ->
                screen.showTab(MainScreen.Tab.FOLDERS)
                screen.listTitleHeight()
            }
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port")
        val upright =
            stopped().use { screen ->
                screen.showTab(MainScreen.Tab.FOLDERS)
                screen.listTitleHeight()
            }
        assertTrue("sideways $sideways, upright $upright", sideways < upright * 0.8f)
    }
}
