package com.nutomic.syncthingandroid.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitPreference
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The run conditions settings screen: what the controls save and show. The text field dialogs are
 * not driven: their focused field keeps Compose busy in Robolectric (docs/TESTING.md).
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS-w411dp-h891dp-xxhdpi",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SettingsRunConditionsScreenTest {

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
                .putExtra(SettingsActivity.EXTRA_START_DESTINATION, "RunConditions")
        )

    private fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())

    /** Scrolls the list to the row titled [title] and returns it. */
    private fun scrolledRow(title: String) =
        row(title).also {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        }

    @Test
    fun `turning Wi-Fi off saves it and disables the Wi-Fi only options`() {
        launch().use {
            row("Run on Wi-Fi").performClick()

            row("Run on Wi-Fi").assertIsOff()
            row("Run on metered Wi-Fi").assertIsNotEnabled()
            row("Run on specified Wi-Fi networks").assertIsNotEnabled()
            compose.awaitPreference { !prefs.getBoolean(Constants.PREF_RUN_ON_WIFI, true) }
        }
    }

    @Test
    fun `the schedule rows show the saved durations`() {
        prefs
            .edit()
            .putString(Constants.PREF_SYNC_DURATION_MINUTES, "90")
            .putString(Constants.PREF_SLEEP_INTERVAL_MINUTES, "240")
            .commit()

        launch().use {
            scrolledRow("Duration of the sync pause interval")
            compose.onNodeWithText("90", substring = true).assertExists()
            compose.onNodeWithText("240", substring = true).assertExists()
        }
    }

    @Test
    fun `known Wi-Fi networks can be picked once location is allowed`() {
        prefs
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_WIFI, true)
            .putBoolean(Constants.PREF_USE_WIFI_SSID_WHITELIST, true)
            .putStringSet(Constants.PREF_KNOWN_WIFI_SSIDS, setOf("\"home\"", "\"office\""))
            .commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(locationManager).setLocationEnabled(true)

        launch().use {
            scrolledRow("Select Wi-Fi networks").performClick()
            // Shown without the quotes Android puts around a network name.
            compose.onNodeWithText("home").performClick()
            compose.onNodeWithText("OK").performClick()

            compose.awaitPreference {
                prefs.getStringSet(Constants.PREF_WIFI_SSID_WHITELIST, null) == setOf("\"home\"")
            }
            compose.onNodeWithText("home", substring = true).assertExists()
        }
    }
}
