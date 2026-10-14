package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.util.formatPath
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Sharing files from another app into a Syncthing folder: the folder choice, the file name, the
 * copy (also when the file already exists), and leaving without sharing.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
        ],
)
class ShareActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folderDir: File = Files.createTempDirectory("camera").toFile()
    private val sourceDir: File = Files.createTempDirectory("shared").toFile()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // LocalBroadcastManager is a process-wide singleton that would keep an earlier test's
        // context.
        LocalBroadcastManager::class.java.getDeclaredField("mInstance").apply {
            isAccessible = true
            set(null, null)
        }
        ConfigFixture.install(context) {
            it.replace("path=\"/storage/emulated/0/DCIM/Camera\"", "path=\"${folderDir.path}\"")
        }
        StoppedSyncthingService.install()
    }

    @After
    fun tearDown() {
        folderDir.deleteRecursively()
        sourceDir.deleteRecursively()
    }

    private fun sharedFile(name: String, content: String): Uri =
        Uri.fromFile(File(sourceDir, name).apply { writeText(content) })

    private fun share(vararg uris: Uri): ActivityScenario<ShareActivity> =
        ActivityScenario.launch(
            Intent(context, ShareActivity::class.java).apply {
                if (uris.size == 1) {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uris.single())
                } else {
                    action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
                }
                type = "text/plain"
            }
        )

    /** Clicks share and waits for the copy, which runs on a background thread. */
    private fun ActivityScenario<ShareActivity>.shareAndWait() {
        onActivity { it.findViewById<View>(R.id.share_button).performClick() }
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (ShadowToast.getLatestToast() == null) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the copy" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(STEP_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ActivityScenario<ShareActivity>.assertFinishing() = onActivity {
        assertTrue(it.isFinishing)
    }

    @Test
    fun `nothing to share closes the screen`() {
        ActivityScenario.launch<ShareActivity>(
                Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND)
            )
            .use { scenario ->
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
                assertEquals("Nothing to share", ShadowToast.getTextOfLatestToast())
            }
    }

    @Test
    fun `the screen offers the folders and the shared file's name`() {
        share(sharedFile("notes.txt", "hello")).use { scenario ->
            scenario.onActivity {
                val folder = it.findViewById<Spinner>(R.id.folders).selectedItem as Folder
                assertEquals("cam-photos", folder.id)
                assertEquals("notes.txt", it.findViewById<EditText>(R.id.name).text.toString())
                assertEquals(
                    "File Name",
                    it.findViewById<TextView>(R.id.namesTitle).text.toString(),
                )
            }
        }
    }

    @Test
    fun `sharing copies the file into the folder under the chosen name`() {
        share(sharedFile("notes.txt", "hello")).use { scenario ->
            scenario.onActivity { it.findViewById<EditText>(R.id.name).setText("renamed.txt") }

            scenario.shareAndWait()

            assertEquals("hello", File(folderDir, "renamed.txt").readText())
            assertEquals("1 file copied to folder \"Camera\"", ShadowToast.getTextOfLatestToast())
            scenario.assertFinishing()
        }
    }

    @Test
    fun `sharing several files copies them all`() {
        share(sharedFile("a.txt", "A"), sharedFile("b.txt", "B")).use { scenario ->
            scenario.onActivity {
                assertEquals(
                    "Files List",
                    it.findViewById<TextView>(R.id.namesTitle).text.toString(),
                )
            }

            scenario.shareAndWait()

            assertEquals("A", File(folderDir, "a.txt").readText())
            assertEquals("B", File(folderDir, "b.txt").readText())
            assertEquals("2 files copied to folder \"Camera\"", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `an existing file is kept unless overwriting is allowed`() {
        File(folderDir, "notes.txt").writeText("old")

        share(sharedFile("notes.txt", "new")).use { scenario ->
            scenario.shareAndWait()

            assertEquals("old", File(folderDir, "notes.txt").readText())
            assertEquals(
                "0 files copied to folder \"Camera\", 1 already exist",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun `cancel leaves without copying`() {
        share(sharedFile("notes.txt", "hello")).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.cancel_button).performClick() }
            shadowOf(Looper.getMainLooper()).idle()

            scenario.assertFinishing()
            assertEquals(emptyList<String>(), folderDir.list()?.toList())
        }
    }

    @Test
    fun `browse opens the folder picker inside the chosen folder`() {
        share(sharedFile("notes.txt", "hello")).use {
            it.onActivity { activity ->
                activity.findViewById<View>(R.id.browse_button).performClick()
            }

            val started = checkNotNull(shadowOf(context as Application).nextStartedActivity)
            assertEquals(FolderPickerActivity::class.java.name, started.component?.className)
        }
    }

    @Test
    fun `a sub folder from the picker is used for the copy and remembered`() {
        File(folderDir, "inbox").mkdirs()
        share(sharedFile("notes.txt", "hello")).use { scenario ->
            browse(scenario)
            val started = checkNotNull(shadowOf(context as Application).nextStartedActivity)
            scenario.onActivity {
                shadowOf(it)
                    .receiveResult(
                        started,
                        Activity.RESULT_OK,
                        Intent()
                            .putExtra(
                                FolderPickerActivity.EXTRA_RESULT_DIRECTORY,
                                formatPath(File(folderDir, "inbox").path),
                            ),
                    )
            }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.shareAndWait()

            assertEquals("hello", File(folderDir, "inbox/notes.txt").readText())
        }
        assertEquals(
            "inbox/",
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + "cam-photos", null),
        )
    }

    @Test
    fun `with the time schedule on, sharing asks Syncthing to run now`() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, true)
            .commit()
        val received = mutableListOf<Intent>()
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        received += intent
                    }
                },
                IntentFilter(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED),
            )

        share(sharedFile("notes.txt", "hello")).use { it.shareAndWait() }

        assertTrue(
            received
                .single()
                .getBooleanExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, false)
        )
    }

    private fun browse(scenario: ActivityScenario<ShareActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.browse_button).performClick() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val STEP_MILLIS = 20L
    }
}
