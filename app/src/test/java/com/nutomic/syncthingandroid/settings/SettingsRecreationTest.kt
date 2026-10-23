package com.nutomic.syncthingandroid.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Settings screens with forms, dialogs or work in flight keep them across a recreation. */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS-w411dp-h891dp-xxhdpi",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SettingsRecreationTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val files = TemporaryFolder()

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

    private fun ActivityScenario<SettingsActivity>.current(): SettingsActivity {
        lateinit var activity: SettingsActivity
        onActivity { activity = it }
        return activity
    }

    @Test
    fun `a certificate reset that ends after a rotation still closes settings`() {
        launch("CustomCertificate").use { scenario ->
            // The service puts the reset off while Syncthing starts.
            RunningSyncthingService.setState(service, SyncthingService.State.STARTING)
            compose
                .onNodeWithText("Reset to auto-generated certificate")
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("OK").performClick()

            scenario.recreate()
            val activity = scenario.current()
            assertFalse(activity.isFinishing)

            RunningSyncthingService.setState(service, SyncthingService.State.DISABLED)
            // The reset runs once the lifecycle executor has seen Syncthing exit.
            awaitMain {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                ShadowToast.getTextOfLatestToast() != null
            }
            compose.waitForIdle()

            assertEquals(
                "Reset to a new self-signed certificate.",
                ShadowToast.getTextOfLatestToast(),
            )
            assertTrue(activity.isFinishing)
        }
    }

    @Test
    fun `picked certificate files survive a recreation`() {
        launch("CustomCertificate").use { scenario ->
            pick(scenario, "Select certificate (.pem)", "certs/leaf-cert.pem")
            pick(scenario, "Select private key (.pem)", "certs/leaf-key.pem")
            // A file URI has no display name, so both rows fall back to the same one.
            compose.onAllNodesWithText("selected file").assertCountEquals(2)

            scenario.recreate()

            compose.onAllNodesWithText("selected file").assertCountEquals(2)
        }
    }

    @Test
    fun `the export question stays open across a recreation`() {
        launch("ImportExport").use { scenario ->
            compose.onNodeWithText("Export Configuration").performScrollTo().performClick()

            scenario.recreate()

            compose
                .onNodeWithText(
                    "Do you really want to export your configuration? " +
                        "The existing archive will be overwritten."
                )
                .assertIsDisplayed()
        }
    }

    @Test
    fun `the power source choice stays open across a recreation`() {
        launch("RunConditions").use { scenario ->
            compose.onNodeWithText("Run when device is powered by").performScrollTo().performClick()

            scenario.recreate()

            compose.onAllNodesWithText("Run when device is powered by").assertCountEquals(2)
        }
    }

    /** Taps the row titled [title] and answers the file picker with the fixture at [fixture]. */
    private fun pick(scenario: ActivityScenario<SettingsActivity>, title: String, fixture: String) {
        compose.onNodeWithText(title).performScrollTo().performClick()
        val file = File(files.root, fixture.substringAfterLast('/'))
        file.writeBytes(Fixtures.bytes(fixture))
        scenario.onActivity { activity ->
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult
            shadow.receiveResult(
                request.intent,
                Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(file)),
            )
        }
        compose.waitForIdle()
    }
}
