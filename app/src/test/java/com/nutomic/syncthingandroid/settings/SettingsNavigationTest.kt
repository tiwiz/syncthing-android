package com.nutomic.syncthingandroid.settings

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Clicks through [SettingsActivity]: forward to a screen, back with the top bar arrow. */
@RunWith(AndroidJUnit4::class)
@Config(
    // Tall enough for the whole root list.
    qualifiers = "en-rUS-w411dp-h891dp-xxhdpi",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SettingsNavigationTest {

    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(ApplicationProvider.getApplicationContext())
        StoppedSyncthingService.install()
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
            compose.onNodeWithText("Syncthing-Fork Wrapper Version").assertIsDisplayed()

            compose.onNodeWithContentDescription("Back").performClick()

            compose.onNodeWithText("Syncthing-Fork Wrapper Version").assertDoesNotExist()
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
}
