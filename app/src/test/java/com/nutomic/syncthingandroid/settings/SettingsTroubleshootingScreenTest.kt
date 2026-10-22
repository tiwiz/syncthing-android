package com.nutomic.syncthingandroid.settings

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The troubleshooting settings screen: the resets ask first and then go to the service, the log
 * opens, debug facilities are saved. Confirming the verbose log restarts the process, so only its
 * cancel is driven.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS-w411dp-h891dp-xxhdpi",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SettingsTroubleshootingScreenTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(app)
        StoppedSyncthingService.install()
    }

    private fun launch(): ActivityScenario<SettingsActivity> =
        ActivityScenario.launch(
            Intent(app, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_START_DESTINATION, "Troubleshooting")
        )

    private fun clickRow(title: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasClickAction()).performClick()
    }

    /** The services started since the last call, by action. */
    private fun startedServiceActions(): List<String?> = generateSequence {
        shadowOf(app).nextStartedService
    }
        .filter { it.component?.className == SyncthingService::class.java.name }
        .map { it.action }
        .toList()

    @Test
    fun `resetting the database asks first, then has the service do it`() {
        launch().use {
            startedServiceActions()
            clickRow("Reset Database")
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(emptyList<String?>(), startedServiceActions())

            clickRow("Reset Database")
            compose.onNodeWithText("OK").performClick()
            assertEquals(listOf(SyncthingService.ACTION_RESET_DATABASE), startedServiceActions())
            assertEquals(
                "Successfully reset Syncthing's database",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun `resetting the delta indexes asks first, then has the service do it`() {
        launch().use {
            startedServiceActions()
            clickRow("Reset Delta Indexes")
            compose.onNodeWithText("OK").performClick()

            assertEquals(listOf(SyncthingService.ACTION_RESET_DELTAS), startedServiceActions())
            assertEquals(
                "Successfully reset Syncthing's delta indexes",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun `reporting an issue opens this app's issue tracker`() {
        val tracker = "https://github.com/tiwiz/syncthing-android/issues"
        launch().use {
            compose
                .onNodeWithText("Open the Expressive Syncthing issue tracker at $tracker")
                .assertExists()
            clickRow("Report Issue")

            val opened = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened?.action)
            assertEquals(tracker, opened?.dataString)
        }
    }

    @Test
    fun `the log opens from here`() {
        launch().use {
            clickRow("Open Log")

            assertEquals(
                LogActivity::class.java.name,
                shadowOf(app).nextStartedActivity?.component?.className,
            )
        }
    }

    @Test
    fun `chosen debug facilities are saved`() {
        launch().use {
            clickRow("STTRACE Options")
            compose.onNodeWithText("db").performClick()
            compose.onNodeWithText("OK").performClick()
            compose.awaitPreference {
                prefs.getStringSet(Constants.PREF_DEBUG_FACILITIES_ENABLED, null) == setOf("db")
            }
            compose.onNodeWithText("No debug facility").assertDoesNotExist()
        }
    }

    @Test
    fun `cancelling the verbose log restart leaves it off`() {
        launch().use {
            clickRow("Verbose log")
            compose.onNodeWithText("Restart required").assertExists()
            compose.onNodeWithText("Cancel").performClick()

            assertFalse(prefs.getBoolean(Constants.PREF_VERBOSE_LOG, false))
            compose.onNode(hasText("Verbose log") and hasClickAction()).assertIsOff()
            assertNull(shadowOf(app).nextStartedActivity)
        }
    }
}
