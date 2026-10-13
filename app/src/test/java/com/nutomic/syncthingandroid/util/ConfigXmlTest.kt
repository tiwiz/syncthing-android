package com.nutomic.syncthingandroid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ConfigFixture.gson
import com.nutomic.syncthingandroid.testutil.Golden
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterizes how the app reads and rewrites Syncthing's `config.xml` while Syncthing is not
 * running. Saved files are produced by the JVM's XML transformer under Robolectric, which may
 * format slightly differently from Android's; the golden files pin the JVM output.
 */
@RunWith(AndroidJUnit4::class)
class ConfigXmlTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var savedOsVersion: String? = null

    @Before
    fun pinKernelVersion() {
        // Constants.osHasKernelBugIssue505() reads the host kernel version.
        savedOsVersion = System.getProperty("os.version")
        System.setProperty("os.version", "6.1.0-android")
    }

    @After
    fun restoreKernelVersion() {
        System.setProperty("os.version", savedOsVersion.orEmpty())
    }

    private fun loaded(transform: (String) -> String = { it }): ConfigXml {
        ConfigFixture.install(context, transform)
        return ConfigXml(context).apply { loadConfig() }
    }

    private fun savedFile() = Constants.getConfigFile(context).readText()

    @Test
    fun `loading rewrites the config with the android defaults`() {
        loaded()
        Golden.assertMatches("configxml/after-load.xml", savedFile())
    }

    @Test
    fun `loading an already migrated config does not rewrite it`() {
        loaded()
        val first = savedFile()
        val modified = Constants.getConfigFile(context).lastModified()
        Constants.getConfigFile(context).setLastModified(modified - 10_000)

        ConfigXml(context).loadConfig()

        assertEquals(first, savedFile())
        assertEquals(modified - 10_000, Constants.getConfigFile(context).lastModified())
    }

    @Test
    fun `config version 27 is migrated to 28 with the file watcher enabled`() {
        loaded {
            it.replaceFirst("<configuration version=\"52\">", "<configuration version=\"27\">")
        }
        Golden.assertMatches("configxml/after-migration-27.xml", savedFile())
    }

    @Test
    fun `known unacked notifications are dropped and the camera folder is added when enabled`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true)
            .commit()
        loaded {
            it.replaceFirst(
                "<options>",
                "<options>\n        <unackedNotificationID>fsWatcherNotification</unackedNotificationID>" +
                    "\n        <unackedNotificationID>somethingElse</unackedNotificationID>",
            )
        }
        // The camera folder gets a random id suffix and a path in Robolectric's temp dir.
        val normalized =
            savedFile()
                .replace(
                    Regex("syncthingAndroidCamera-[a-z0-9]{5}-[a-z0-9]{5}"),
                    "syncthingAndroidCamera-RANDOM",
                )
                .replace(Regex("/tmp/robolectric-[^/\"]+"), "<robolectric-tmp>")
        Golden.assertMatches("configxml/after-load-camera-and-notifications.xml", normalized)
    }

    @Test
    fun `gui settings`() {
        val config = loaded()
        assertEquals("https://127.0.0.1:8384", config.webGuiUrl.toString())
        assertEquals(8384, config.webGuiBindPort)
        assertEquals("fixture-api-key", config.apiKey)
        assertEquals("", config.webUIUsername)
        Golden.assertMatches("configxml/gui.json", gson.toJson(config.gui))
        Golden.assertMatches("configxml/options.json", gson.toJson(config.options))
    }

    @Test
    fun `folders and devices`() {
        val config = loaded()
        Golden.assertMatches("configxml/folders.json", gson.toJson(config.folders))
        Golden.assertMatches(
            "configxml/devices-with-local.json",
            gson.toJson(config.getDevices(true)),
        )
        assertEquals(listOf(REMOTE_DEVICE_ID), config.getDevices(false).map { it.deviceID })
    }

    @Test
    fun `add, update and remove a folder`() {
        val config = loaded()
        val folder =
            Folder().apply {
                id = "music-abcd"
                label = "Music"
                path = "/storage/emulated/0/Music"
                type = "receiveonly"
                versioning = Folder.Versioning()
            }
        config.addFolder(folder)
        config.saveChanges()
        Golden.assertMatches(
            "configxml/folder-added.json",
            gson.toJson(ConfigXml(context).apply { loadConfig() }.folders),
        )

        val reloaded = ConfigXml(context).apply { loadConfig() }
        val music = reloaded.folders.first { it.id == "music-abcd" }
        music.label = "Music (renamed)"
        music.paused = true
        reloaded.updateFolder(music)
        reloaded.setFolderPause("cam-photos", true)
        reloaded.saveChanges()
        Golden.assertMatches(
            "configxml/folder-updated.json",
            gson.toJson(ConfigXml(context).apply { loadConfig() }.folders),
        )

        val again = ConfigXml(context).apply { loadConfig() }
        again.removeFolder("music-abcd")
        again.saveChanges()
        assertEquals(
            listOf("cam-photos"),
            ConfigXml(context).apply { loadConfig() }.folders.map { it.id },
        )
    }

    @Test
    fun `a folder without versioning is saved like one with versioning off`() {
        fun saveFolder(versioning: Folder.Versioning?): String {
            val config = loaded()
            config.addFolder(
                Folder().apply {
                    id = "x"
                    path = "/x"
                    this.versioning = versioning
                }
            )
            config.saveChanges()
            return savedFile()
        }

        assertEquals(saveFolder(Folder.Versioning()), saveFolder(null))
    }

    @Test
    fun `update and remove a device`() {
        val config = loaded()
        val laptop = config.getDevices(false).single()
        laptop.name = "work laptop"
        laptop.addresses = mutableListOf("tcp://192.0.2.20:22000")
        laptop.compression = "always"
        config.updateDevice(laptop)
        config.setDevicePause(REMOTE_DEVICE_ID, true)
        config.saveChanges()
        Golden.assertMatches(
            "configxml/device-updated.json",
            gson.toJson(ConfigXml(context).apply { loadConfig() }.getDevices(true)),
        )

        val again = ConfigXml(context).apply { loadConfig() }
        again.removeDevice(REMOTE_DEVICE_ID)
        again.saveChanges()
        val remaining = ConfigXml(context).apply { loadConfig() }
        assertEquals(listOf(LOCAL_DEVICE_ID), remaining.getDevices(true).map { it.deviceID })
        // Like Syncthing, which drops folder devices that no longer exist.
        assertEquals(
            emptyList<String>(),
            remaining.folders.single().sharedWithDevices!!.map { it.deviceID },
        )
    }

    @Test
    fun `update gui`() {
        val config = loaded()
        val gui = config.gui
        gui.address = "0.0.0.0:8384"
        gui.user = "admin"
        config.updateGui(gui)
        config.saveChanges()
        val reloaded = ConfigXml(context).apply { loadConfig() }
        assertEquals("0.0.0.0:8384", reloaded.gui.address)
        assertEquals("admin", reloaded.webUIUsername)
    }

    @Test
    fun `missing or broken config file`() {
        Constants.getConfigFile(context).delete()
        assertThrows(ConfigXml.OpenConfigException::class.java) { ConfigXml(context).loadConfig() }

        ConfigFixture.install(context) { "<configuration" }
        assertThrows(ConfigXml.OpenConfigException::class.java) { ConfigXml(context).loadConfig() }

        ConfigFixture.install(context) { it.replace(Regex("(?s)<gui .*?</gui>"), "") }
        assertThrows(ConfigXml.OpenConfigException::class.java) { ConfigXml(context).loadConfig() }
    }

    @Test
    fun `ignore list is read from the folder's stignore file`() {
        val config = loaded()
        val folderDir = kotlin.io.path.createTempDirectory("folder").toFile()
        val folder = Folder().apply { path = folderDir.absolutePath }

        var none: Array<String>? = arrayOf("not called")
        config.getFolderIgnoreList(folder) { none = it.ignore }

        config.postFolderIgnoreList(folder, arrayOf("*.tmp", "// comment", "!keep.tmp"))
        var read: Array<String>? = null
        config.getFolderIgnoreList(folder) { read = it.ignore }

        Golden.assertMatches(
            "configxml/ignore-list.txt",
            "missing file -> ${none?.toList()}\n" +
                "after post -> ${read?.toList()}\n" +
                "file -> ${java.io.File(folderDir, Constants.FILENAME_STIGNORE).readText().replace("\n", "\\n")}",
        )
        assertFalse(java.io.File(folderDir, Constants.FILENAME_STIGNORE).readText().isEmpty())
    }
}
