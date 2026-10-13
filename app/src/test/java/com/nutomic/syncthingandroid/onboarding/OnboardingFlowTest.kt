package com.nutomic.syncthingandroid.onboarding

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.os.PowerManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The launcher flow: straight to the app when everything is in place, otherwise the pages that are
 * missing, with key generation through [FakeSyncthingBinary].
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class OnboardingFlowTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // Every permission page is satisfied; only the config decides.
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)
    }

    private fun startedActivities(): List<ComponentName?> = generateSequence {
        shadowOf(app).nextStartedActivity
    }
        .map { it.component }
        .toList()

    @Test
    fun `with permissions and a config it goes straight to the main screen`() {
        ConfigFixture.install(app)

        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            assertEquals(listOf(ComponentName(app, MainActivity::class.java)), startedActivities())
            // It finishes in onCreate, so it is already gone.
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun `start into web GUI opens the web GUI on top of the main screen`() {
        ConfigFixture.install(app)
        prefs.edit().putBoolean(Constants.PREF_START_INTO_WEB_GUI, true).commit()

        ActivityScenario.launch(OnboardingActivity::class.java).use {
            // Started together with startActivities, main first so Back from the GUI lands there;
            // Robolectric records them in reverse, so only the set is checked.
            assertEquals(
                setOf(
                    ComponentName(app, MainActivity::class.java),
                    ComponentName(app, WebGuiActivity::class.java),
                ),
                startedActivities().toSet(),
            )
        }
    }

    @Test
    fun `without a config the keys are generated, then Finish opens the app`() {
        FakeSyncthingBinary(app)
            .apply {
                generatedConfig = Fixtures.text("syncthing/config.xml")
                stdout = "$LOCAL_DEVICE_ID\n"
            }
            .install()

        ActivityScenario.launch(OnboardingActivity::class.java).use {
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTextCount(
                    "Secure keys for private data exchange have been successfully generated."
                ) > 0
            }

            assertTrue(Constants.getConfigFile(app).exists())
            assertEquals(LOCAL_DEVICE_ID, prefs.getString(Constants.PREF_LOCAL_DEVICE_ID, null))
            assertNull(shadowOf(app).nextStartedActivity)

            compose.onNodeWithText("Finish").performClick()
            assertEquals(listOf(ComponentName(app, MainActivity::class.java)), startedActivities())
        }
    }

    @Test
    fun `a missing binary is shown as a key generation error`() {
        FakeSyncthingBinary(app).remove()

        ActivityScenario.launch(OnboardingActivity::class.java).use {
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTextCount("Core executable", substring = true) > 0
            }

            compose.onNodeWithText("Core executable", substring = true).assertIsDisplayed()
            assertTrue(!Constants.getConfigFile(app).exists())
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(
        text: String,
        substring: Boolean = false,
    ): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring))
            .fetchSemanticsNodes()
            .size
}
