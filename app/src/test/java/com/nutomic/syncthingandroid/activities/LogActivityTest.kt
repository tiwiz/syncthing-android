package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
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
import com.nutomic.syncthingandroid.testutil.awaitMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
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

    private fun ActivityScenario<LogActivity>.shown(): String {
        var text = ""
        onActivity { text = it.findViewById<TextView>(R.id.log).text.toString() }
        return text
    }

    private fun ActivityScenario<LogActivity>.awaitShown(expected: String) {
        try {
            awaitMain { shown() == expected }
        } catch (e: IllegalStateException) {
            throw AssertionError("shown: [${shown()}]", e)
        }
    }

    private fun ActivityScenario<LogActivity>.switchLogs() {
        onActivity { it.onOptionsItemSelected(RoboMenuItem(R.id.switch_logs)) }
    }

    private fun ActivityScenario<LogActivity>.share() {
        onActivity { it.onOptionsItemSelected(RoboMenuItem(R.id.menu_share_log_file)) }
    }

    // Removing the milliseconds also removes the space before the tag, as it always did.
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
                "10:00:00I/SyncthingService starting\n10:00:02 [ABCDE] INFO: ready\n" +
                    "10:00:03E/RestApi failed"
            it.awaitShown(expected)
            assertEquals(expected, Constants.getAndroidLogFile(context).readText())
        }
    }

    @Test
    fun `the Syncthing log comes from its file`() {
        Constants.getSyncthingLogFile(context).writeText("[ABCDE] INFO: Syncthing log\n")

        ShadowUtilDeviceShell.logcatOutput = "10-13 10:00:00.000 I/SyncthingService(1): up"

        launch().use {
            it.awaitShown("10:00:00I/SyncthingService up")
            it.switchLogs()
            it.awaitShown("[ABCDE] INFO: Syncthing log\n")
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
}
