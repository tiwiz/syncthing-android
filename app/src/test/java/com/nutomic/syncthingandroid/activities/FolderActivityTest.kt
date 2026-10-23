package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.util.ConfigXml
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The folder screen with Syncthing stopped (changes go to `config.xml`): editing, creating and
 * removing folders, the settings chosen on other screens, device shares and the ignore list. The
 * screen is driven through [FolderScreen].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
)
class FolderActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val folderDir: File = Files.createTempDirectory("camera").toFile()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // LocalBroadcastManager is a process-wide singleton that would keep an earlier test's
        // context.
        LocalBroadcastManager::class.java.getDeclaredField("mInstance").apply {
            isAccessible = true
            set(null, null)
        }
        // A folder path the app can write to, so every folder type is offered.
        ConfigFixture.install(context) {
            it.replace("path=\"/storage/emulated/0/DCIM/Camera\"", "path=\"${folderDir.path}\"")
        }
        StoppedSyncthingService.install()
    }

    @After
    fun tearDown() {
        folderDir.setWritable(true)
        folderDir.deleteRecursively()
    }

    private fun savedConfig() = ConfigXml(context).apply { loadConfig() }

    private fun savedFolder() = savedConfig().folders.single()

    private fun editFolder() =
        FolderScreen(
            ActivityScenario.launchActivityForResult(
                Intent(context, FolderActivity::class.java)
                    .putExtra(FolderActivity.EXTRA_IS_CREATE, false)
                    .putExtra(FolderActivity.EXTRA_FOLDER_ID, "cam-photos")
            ),
            compose,
        )

    private fun newFolder(extras: Intent.() -> Unit = {}) =
        FolderScreen(
            ActivityScenario.launchActivityForResult(
                FolderActivity.createIntent(context).apply(extras)
            ),
            compose,
        )

    @Test
    fun `renaming a folder is saved to config xml`() {
        editFolder().use { screen ->
            screen.setLabel("Photos")
            screen.save()

            assertEquals(Activity.RESULT_OK, screen.resultCode())
            assertEquals("Photos", savedFolder().label)
        }
    }

    @Test
    fun `unsaved changes survive recreation`() {
        editFolder().use { screen ->
            screen.setLabel("Photos")

            screen.recreate()
            assertEquals("Photos", screen.label())

            screen.save()
            assertEquals("Photos", savedFolder().label)
        }
    }

    @Test
    fun `saving an unchanged folder writes nothing`() {
        editFolder().use { screen ->
            screen.save()

            assertEquals(Activity.RESULT_CANCELED, screen.resultCode())
            assertEquals("Camera", savedFolder().label)
        }
    }

    @Test
    fun `a folder without a label is not saved`() {
        editFolder().use { screen ->
            screen.setLabel("")
            screen.save()

            assertEquals("The folder label must not be empty", ShadowToast.getTextOfLatestToast())
            assertFalse(screen.finishing())
            assertEquals("Camera", savedFolder().label)
        }
    }

    @Test
    fun `removing a folder asks first, then deletes it from config xml`() {
        editFolder().use { screen ->
            screen.delete()
            screen.confirmDialog()

            assertTrue(screen.finishing())
            assertEquals(emptyList<String>(), savedConfig().folders.map { it.id })
        }
    }

    @Test
    fun `removing the camera folder withdraws the consent to Syncthing Camera`() {
        ConfigFixture.install(context) {
            it.replace("id=\"cam-photos\"", "id=\"${Constants.SYNCTHING_CAMERA_FOLDER_ID}\"")
                .replace("path=\"/storage/emulated/0/DCIM/Camera\"", "path=\"${folderDir.path}\"")
        }
        prefs.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true).commit()

        FolderScreen(
                ActivityScenario.launchActivityForResult(
                    Intent(context, FolderActivity::class.java)
                        .putExtra(
                            FolderActivity.EXTRA_FOLDER_ID,
                            Constants.SYNCTHING_CAMERA_FOLDER_ID,
                        )
                ),
                compose,
            )
            .use { screen ->
                screen.delete()
                screen.confirmDialog()
            }

        assertFalse(prefs.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true))
    }

    @Test
    fun `leaving with unsaved changes asks first`() {
        editFolder().use { screen ->
            screen.setLabel("Photos")
            screen.back()
            assertFalse(screen.finishing())

            screen.confirmDialog()

            assertTrue(screen.finishing())
            assertEquals("Camera", savedFolder().label)
        }
    }

    @Test
    fun `a new folder type from its screen is shown and saved`() {
        editFolder().use { screen ->
            val started = checkNotNull(screen.open(FolderScreen.Opener.FOLDER_TYPE))
            assertEquals(FolderTypeDialogActivity::class.java.name, started.component?.className)
            assertEquals(
                Constants.FOLDER_TYPE_SEND_ONLY,
                started.getStringExtra(FolderTypeDialogActivity.EXTRA_FOLDER_TYPE),
            )

            screen.receive(
                started,
                Intent()
                    .putExtra(
                        FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE,
                        Constants.FOLDER_TYPE_SEND_RECEIVE,
                    ),
            )
            assertEquals("Send & Receive", screen.shown(FolderScreen.Shown.FOLDER_TYPE))
            screen.save()

            assertEquals(Constants.FOLDER_TYPE_SEND_RECEIVE, savedFolder().type)
        }
    }

    @Test
    fun `an existing folder cannot become receive encrypted`() {
        editFolder().use { screen ->
            val started = checkNotNull(screen.open(FolderScreen.Opener.FOLDER_TYPE))

            screen.receive(
                started,
                Intent()
                    .putExtra(
                        FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE,
                        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED,
                    ),
            )

            assertEquals(
                "Switching existing folders to 'Receive Encrypted' is not supported.",
                ShadowToast.getTextOfLatestToast(),
            )
            screen.save()
            assertEquals(Constants.FOLDER_TYPE_SEND_ONLY, savedFolder().type)
        }
    }

    @Test
    fun `a read-only folder offers no other folder type`() {
        folderDir.setWritable(false)

        editFolder().use { screen ->
            assertNull(screen.open(FolderScreen.Opener.FOLDER_TYPE))
            assertEquals(
                "Your Android version only grants Syncthing readonly access to the selected folder.",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun `a new pull order from its screen is shown and saved`() {
        prefs.edit().putBoolean(Constants.PREF_EXPERT_MODE, true).commit()
        // Send-only folders pull nothing, so they offer no pull order.
        ConfigFixture.install(context) {
            it.replace("path=\"/storage/emulated/0/DCIM/Camera\"", "path=\"${folderDir.path}\"")
                .replace("type=\"sendonly\"", "type=\"sendreceive\"")
        }
        editFolder().use { screen ->
            val started = checkNotNull(screen.open(FolderScreen.Opener.PULL_ORDER))
            assertEquals(PullOrderDialogActivity::class.java.name, started.component?.className)
            assertEquals("random", started.getStringExtra(PullOrderDialogActivity.EXTRA_PULL_ORDER))

            screen.receive(
                started,
                Intent().putExtra(PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER, "oldestFirst"),
            )
            assertEquals("Oldest First", screen.shown(FolderScreen.Shown.PULL_ORDER))
            screen.save()

            assertEquals("oldestFirst", savedFolder().order)
        }
    }

    @Test
    fun `send-only folders offer no pull order`() {
        prefs.edit().putBoolean(Constants.PREF_EXPERT_MODE, true).commit()

        editFolder().use { screen -> assertFalse(screen.offers(FolderScreen.Opener.PULL_ORDER)) }
    }

    @Test
    fun `new versioning from its screen is shown and saved`() {
        editFolder().use { screen ->
            val started = checkNotNull(screen.open(FolderScreen.Opener.VERSIONING))
            assertEquals(VersioningDialogActivity::class.java.name, started.component?.className)

            screen.receive(started, Intent().putExtra("type", "simple").putExtra("keep", "7"))
            assertEquals("Type: Simple", screen.shown(FolderScreen.Shown.VERSIONING))
            assertEquals(
                "Keep versions: 7",
                screen.shown(FolderScreen.Shown.VERSIONING_DESCRIPTION),
            )
            screen.save()

            val versioning = checkNotNull(savedFolder().versioning)
            assertEquals("simple", versioning.type)
            assertEquals("7", versioning.params["keep"])
        }
    }

    @Test
    fun `custom sync conditions open for this folder once switched on`() {
        editFolder().use { screen ->
            assertFalse(screen.isEnabled(FolderScreen.Opener.SYNC_CONDITIONS))
            screen.toggle(FolderScreen.Switch.CUSTOM_SYNC_CONDITIONS)
            assertTrue(screen.isEnabled(FolderScreen.Opener.SYNC_CONDITIONS))

            val started = checkNotNull(screen.open(FolderScreen.Opener.SYNC_CONDITIONS))

            assertEquals(SyncConditionsActivity::class.java.name, started.component?.className)
            val expected =
                SyncConditionsActivity.createIntent(
                    context,
                    Constants.PREF_OBJECT_PREFIX_FOLDER + "cam-photos",
                    "Camera",
                )
            for (key in checkNotNull(expected.extras).keySet()) {
                assertEquals(key, expected.getStringExtra(key), started.getStringExtra(key))
            }
        }
    }

    @Test
    fun `switches and per folder prefs are saved`() {
        prefs.edit().putBoolean(Constants.PREF_EXPERT_MODE, true).commit()
        editFolder().use { screen ->
            screen.toggle(FolderScreen.Switch.FILE_WATCHER)
            screen.toggle(FolderScreen.Switch.PAUSED)
            screen.toggle(FolderScreen.Switch.IGNORE_DELETE)
            screen.toggle(FolderScreen.Switch.RUN_SCRIPT)
            screen.toggle(FolderScreen.Switch.CUSTOM_SYNC_CONDITIONS)
            screen.save()
        }

        val folder = savedFolder()
        assertFalse(folder.fsWatcherEnabled)
        assertTrue(folder.paused)
        assertTrue(folder.ignoreDelete)
        assertTrue(prefs.getBoolean(Constants.dynPrefObjectFolderRunScript("cam-photos"), false))
        assertTrue(
            prefs.getBoolean(
                Constants.dynPrefObjectCustomSyncConditions(
                    Constants.PREF_OBJECT_PREFIX_FOLDER + "cam-photos"
                ),
                false,
            )
        )
    }

    @Test
    fun `unsharing a device and setting a password are saved`() {
        editFolder().use { screen ->
            assertTrue(screen.deviceShared("laptop"))
            screen.setDevicePassword("laptop", "s3cret")
            screen.save()
        }
        assertEquals(
            "s3cret",
            checkNotNull(savedFolder().getDevice(REMOTE_DEVICE_ID)).encryptionPassword,
        )

        editFolder().use { screen ->
            screen.toggleDevice("laptop")
            screen.save()
        }
        assertNull(savedFolder().getDevice(REMOTE_DEVICE_ID))
    }

    @Test
    fun `the ignore list is read from and written to the folder`() {
        File(folderDir, ".stignore").writeText("*.tmp")

        editFolder().use { screen ->
            assertEquals("*.tmp", screen.ignoreList())
            screen.setIgnoreList("*.tmp\n!keep")
            screen.save()
        }

        assertEquals("*.tmp\n!keep", File(folderDir, ".stignore").readText())
    }

    @Test
    fun `a new folder in a picked directory is prepared, added and synced`() {
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
        val newDir = File(folderDir, "Music").apply { mkdirs() }

        newFolder().use { screen ->
            screen.setLabel("Music")
            screen.setId("music-1")
            val started = checkNotNull(screen.open(FolderScreen.Opener.ADVANCED_DIRECTORY))
            assertEquals(FolderPickerActivity::class.java.name, started.component?.className)
            screen.receive(
                started,
                Intent().putExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY, newDir.path + "/"),
            )
            assertEquals(newDir.path, screen.shown(FolderScreen.Shown.PATH))
            screen.save()
            awaitMain { received.isNotEmpty() }

            assertEquals(Activity.RESULT_OK, screen.resultCode())
        }

        val added = savedConfig().folders.single { it.id == "music-1" }
        assertEquals("Music", added.label)
        assertEquals(newDir.path, added.path)
        assertEquals("trashcan", checkNotNull(added.versioning).type)
        assertTrue(File(newDir, ".stfolder/DO_NOT_DELETE").isFile)
        assertTrue(File(newDir, ".stversions/.nomedia").isFile)
    }

    @Test
    fun `a folder offered by a device is shared with it`() {
        newFolder {
            putExtra(FolderActivity.EXTRA_FOLDER_ID, "offered")
            putExtra(FolderActivity.EXTRA_FOLDER_LABEL, " Offered ")
            putExtra(FolderActivity.EXTRA_DEVICE_ID, REMOTE_DEVICE_ID)
        }
            .use { screen -> assertTrue(screen.deviceShared("laptop")) }
    }

    @Test
    fun `with Syncthing stopped the folder shows no state`() {
        editFolder().use { screen -> assertNull(screen.state()) }
    }

    @Test
    fun `saving while Syncthing runs keeps the folder settings the app does not edit`() {
        ConfigFixture.install(context) { it.replaceFirst(".stfolder", ".custom") }
        val syncthing = FakeSyncthing(context)
        try {
            syncthing.respond(
                "GET",
                "/rest/system/config",
                syncthing.configJson().replaceFirst("\".stfolder\"", "\".custom\""),
            )
            RunningSyncthingService.install(syncthing)
            editFolder().use { screen ->
                screen.setLabel("Photos")
                screen.save()
                awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }
            }
            val posted =
                JsonParser.parseString(
                        syncthing.requests("/rest/system/config", "POST").last().body!!.utf8()
                    )
                    .asJsonObject
                    .getAsJsonArray("folders")
                    .map { it.asJsonObject }
                    .single { it["id"].asString == "cam-photos" }
            assertEquals("Photos", posted["label"].asString)
            assertEquals(".custom", posted["markerName"].asString)
        } finally {
            syncthing.close()
        }
    }

    @Test
    fun `the folder's state shows while Syncthing runs`() {
        val syncthing = FakeSyncthing(context)
        try {
            RunningSyncthingService.install(syncthing)
            editFolder().use { screen ->
                // Syncthing answers the first query later; the screen shows it on a refresh.
                awaitMain {
                    shadowOf(Looper.getMainLooper())
                        .idleFor(Duration.ofMillis(Constants.GUI_UPDATE_INTERVAL))
                    screen.state() != null
                }
                assertNotNull(screen.state())
            }
        } finally {
            syncthing.close()
        }
    }
}
