package com.nutomic.syncthingandroid.settings

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives [SettingsActivity]: forward to a screen and back with the top bar arrow, and screens
 * following the bound service's state.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    // Tall enough for the whole root list.
    qualifiers = "en-rUS-w411dp-h891dp-xxhdpi",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SettingsNavigationTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var service: SyncthingService

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(ApplicationProvider.getApplicationContext())
        service = StoppedSyncthingService.install()
    }

    private fun launch(route: String): ActivityScenario<SettingsActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_START_DESTINATION, route)
        )

    @Test
    fun `a screen opened from the root goes back to the root`() {
        launch("Root").use {
            compose.onNodeWithText("About").performClick()
            compose.onNodeWithText("Expressive Syncthing Wrapper Version").assertIsDisplayed()

            compose.onNodeWithContentDescription("Back").performClick()

            compose.onNodeWithText("Expressive Syncthing Wrapper Version").assertDoesNotExist()
            compose.onNodeWithText("Settings").assertIsDisplayed()
        }
    }

    @Test
    fun `back on the root screen closes settings`() {
        launch("Root").use { scenario ->
            lateinit var activity: SettingsActivity
            scenario.onActivity { activity = it }

            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()

            assertTrue(activity.isFinishing)
        }
    }

    @Test
    @Config(
        // Expanded window: list and detail should appear side by side.
        qualifiers = "en-rUS-w840dp-h900dp-xxhdpi"
    )
    fun `expanded shows root and detail side by side`() {
        launch("Root").use {
            compose
                .onNode(hasText("About") and hasAnyAncestor(hasTestTag("settingsRootList")))
                .performClick()

            // Detail pane shows the selected screen.
            compose.onNodeWithText("Expressive Syncthing Wrapper Version").assertIsDisplayed()
            // List pane is still visible next to it.
            compose.onNodeWithText("Settings").assertIsDisplayed()
            compose
                .onNode(hasText("About") and hasAnyAncestor(hasTestTag("settingsRootList")))
                .assertIsDisplayed()
        }
    }

    @Test
    fun `screens follow the service state`() {
        launch("Root").use {
            compose.onNodeWithText("Syncthing Options").assertIsNotEnabled()
            compose.onNodeWithText(ONLY_WHEN_RUNNING).assertExists()

            setServiceState(SyncthingService.State.ACTIVE)

            compose.onNodeWithText("Syncthing Options").assertIsEnabled()
            // The hint why the screen is disabled goes once it is not.
            compose.onNodeWithText(ONLY_WHEN_RUNNING).assertDoesNotExist()
        }
    }

    /** Moves the service to [state] the way it does itself, notifying its listeners. */
    private fun setServiceState(state: SyncthingService.State) {
        SyncthingService::class
            .java
            .getDeclaredMethod("onServiceStateChange", SyncthingService.State::class.java)
            .apply { isAccessible = true }
            .invoke(service, state)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val ONLY_WHEN_RUNNING = "This menu is only available if Syncthing is running."
    }
}
