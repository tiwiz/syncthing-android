package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The log screen: the Android log from logcat without the noise of other components, the Syncthing
 * log from its file, and sharing either one.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class LogActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // FileProvider keeps the roots of the first test's directories in a static cache.
        val cache =
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        StoppedSyncthingService.install()
    }

    @After
    fun tearDown() {
        ShadowUtilDeviceShell.logcatOutput = ShadowUtilDeviceShell.FAKE_LOGCAT
    }

    private fun launch() = ActivityScenario.launch(LogActivity::class.java)

    /** Waits until the screen shows exactly [expected] as the log. */
    @Suppress("UnusedReceiverParameter") // Reads like the scenario's own step.
    private fun ActivityScenario<LogActivity>.awaitShown(expected: String) {
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Suppress("UnusedReceiverParameter")
    private fun ActivityScenario<LogActivity>.switchLogs() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("View Syncthing Log").performClick()
    }

    @Suppress("UnusedReceiverParameter")
    private fun ActivityScenario<LogActivity>.share() {
        compose.onNodeWithContentDescription("Share log file").performClick()
    }

    @Test
    fun `the Android log leaves out other components and shortens the lines`() {
        ShadowUtilDeviceShell.logcatOutput =
            """
            --------- beginning of main
            10-13 10:00:00.123 I/SyncthingService( 1234): starting
            10-13 10:00:01.456 W/Choreographer(1234): Skipped 30 frames!
            10-13 10:00:02.789 I/SyncthingNativeCode(4321): [ABCDE] INFO: ready
            10-13 10:00:03.000 E/RestApi(1234): failed
            """
                .trimIndent()

        launch().use {
            val expected =
                "10:00:00 I/SyncthingService starting\n10:00:02 [ABCDE] INFO: ready\n" +
                    "10:00:03 E/RestApi failed"
            it.awaitShown(expected)
            assertEquals(expected, Constants.getAndroidLogFile(context).readText())
        }
    }

    @Test
    fun `the Syncthing log comes from its file`() {
        Constants.getSyncthingLogFile(context).writeText("[ABCDE] INFO: Syncthing log\n")

        ShadowUtilDeviceShell.logcatOutput = "10-13 10:00:00.000 I/SyncthingService(1): up"

        launch().use {
            it.awaitShown("10:00:00 I/SyncthingService up")
            it.switchLogs()
            it.awaitShown("[ABCDE] INFO: Syncthing log\n")
            compose.onNodeWithText("Syncthing Log").assertExists()
        }
    }

    @Test
    fun `a recreation keeps the shown log without reading it again`() {
        Constants.getSyncthingLogFile(context).writeText("[ABCDE] INFO: before\n")

        launch().use {
            it.switchLogs()
            it.awaitShown("[ABCDE] INFO: before\n")
            Constants.getSyncthingLogFile(context).writeText("[ABCDE] INFO: after\n")

            it.recreate()
            compose.waitForIdle()

            compose.onNodeWithText("Syncthing Log").assertExists()
            compose.onNodeWithText("[ABCDE] INFO: before\n").assertExists()
        }
    }

    @Test
    fun `sharing a missing log says so`() {
        Constants.getSyncthingLogFile(context).delete()

        launch().use {
            it.switchLogs()
            it.share()

            assertEquals(
                context.getString(R.string.share_log_file_missing),
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun `the shown log is shared as a file`() {
        Constants.getSyncthingLogFile(context).writeText("log\n")

        launch().use {
            it.switchLogs()
            it.share()

            val chooser = checkNotNull(shadowOf(context as Application).nextStartedActivity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send =
                checkNotNull(
                    IntentCompat.getParcelableExtra(
                        chooser,
                        Intent.EXTRA_INTENT,
                        Intent::class.java,
                    )
                )
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            val stream =
                checkNotNull(
                    IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
                )
            assertEquals("${context.packageName}.provider", stream.authority)
            assertEquals("syncthing.log", stream.lastPathSegment)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
