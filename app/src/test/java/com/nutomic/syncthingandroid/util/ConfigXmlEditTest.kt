package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.Golden
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mindrot.jbcrypt.BCrypt

/**
 * More of ConfigXml: generating a new config, pausing, ignore files, sorting and "~" paths, all
 * with Syncthing stopped.
 */
@RunWith(AndroidJUnit4::class)
class ConfigXmlEditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val tempDir: File = Files.createTempDirectory("folder").toFile()
    private var savedOsVersion: String? = null

    @Before
    fun pinKernelVersion() {
        savedOsVersion = System.getProperty("os.version")
        System.setProperty("os.version", "6.1.0-android")
    }

    @After
    fun tearDown() {
        System.setProperty("os.version", savedOsVersion.orEmpty())
        tempDir.deleteRecursively()
    }

    private fun loaded(transform: (String) -> String = { it }): ConfigXml {
        ConfigFixture.install(context, transform)
        return ConfigXml(context).apply { loadConfig() }
    }

    private fun reloaded() = ConfigXml(context).apply { loadConfig() }

    @Test
    fun `a generated config gets the device name, trash can versioning and a GUI login`() {
        prefs.edit().remove(Constants.PREF_LOCAL_DEVICE_ID).commit()
        FakeSyncthingBinary(context)
            .apply {
                generatedConfig = Fixtures.text("syncthing/config.xml")
                stdout = "$LOCAL_DEVICE_ID\n"
            }
            .install()

        ConfigXml(context).generateConfig()

        val saved = Constants.getConfigFile(context).readText()
        val hash = Regex("<password>([^<]*)</password>").find(saved)?.groupValues?.get(1).orEmpty()
        assertTrue(BCrypt.checkpw("fixture-api-key", hash))
        assertEquals(LOCAL_DEVICE_ID, prefs.getString(Constants.PREF_LOCAL_DEVICE_ID, null))
        assertEquals("fixture-api-key", prefs.getString(Constants.PREF_WEBUI_PASSWORD, null))
        assertEquals("fixture-api-key", reloaded().webUIPassword)
        Golden.assertMatches(
            "configxml/after-generate.xml",
            saved.replace(hash, "<bcrypt>").replace(Build.MODEL, "<device model>"),
        )
    }

    @Test
    fun `a bad device id from the binary is not stored`() {
        prefs.edit().remove(Constants.PREF_LOCAL_DEVICE_ID).commit()
        FakeSyncthingBinary(context)
            .apply {
                generatedConfig = Fixtures.text("syncthing/config.xml")
                stdout = "not-a-device-id\n"
            }
            .install()

        ConfigXml(context).generateConfig()

        assertNull(prefs.getString(Constants.PREF_LOCAL_DEVICE_ID, null))
    }

    @Test
    fun `pausing a folder and a device is saved`() {
        val config = loaded()

        config.setFolderPause("cam-photos", true)
        config.setDevicePause(REMOTE_DEVICE_ID, true)
        config.saveChanges()

        val again = reloaded()
        assertTrue(again.folders.single().paused)
        assertTrue(again.getDevices(false).single().paused)
    }

    @Test
    fun `the ignore list is written to the folder and read back`() {
        val config = loaded()
        val folder = Folder().apply { path = tempDir.path }

        config.postFolderIgnoreList(folder, arrayOf("*.tmp", "// comment", "!keep"))

        assertEquals("*.tmp\n// comment\n!keep", File(tempDir, ".stignore").readText())
        var read: Array<String>? = null
        config.getFolderIgnoreList(folder) { read = it.ignore }
        assertArrayEquals(arrayOf("*.tmp", "// comment", "!keep"), read)
    }

    @Test
    fun `an ignore list in a folder without a stignore file is empty`() {
        var ignore: Array<String>? = arrayOf("not called")

        loaded().getFolderIgnoreList(Folder().apply { path = tempDir.path }) { ignore = it.ignore }

        assertNull(ignore)
    }

    @Test
    fun `folders are sorted by label, or id without one, and home paths are expanded`() {
        val config = loaded()
        config.addFolder(folder("zz-id", label = "Alpha", path = "~/Alpha"))
        config.addFolder(folder("beta-id", label = "", path = "/b"))
        config.saveChanges()

        val folders = reloaded().folders

        // Case-sensitive, like String.compareTo: upper case before lower case.
        assertEquals(listOf("zz-id", "cam-photos", "beta-id"), folders.map { it.id })
        assertEquals(FileUtils.getSyncthingTildeAbsolutePath() + "/Alpha", folders[0].path)
    }

    @Test
    fun `devices are sorted by name, or id without one`() {
        val config = loaded()
        config.updateDevice(device("AAAAAAA-ZULU", name = "zulu"))
        config.updateDevice(device("BBBBBBB-NONAME", name = ""))
        config.saveChanges()

        assertEquals(
            listOf("BBBBBBB-NONAME", REMOTE_DEVICE_ID, "AAAAAAA-ZULU"),
            reloaded().getDevices(false).map { it.deviceID },
        )
    }

    @Test
    fun `the GUI password comes from the preferences`() {
        val config = loaded()
        assertEquals("", config.webUIPassword)

        prefs.edit().putString(Constants.PREF_WEBUI_PASSWORD, "secret").commit()

        assertEquals("secret", config.webUIPassword)
    }

    private fun folder(id: String, label: String, path: String) =
        Folder().apply {
            this.id = id
            this.label = label
            this.path = path
        }

    private fun device(id: String, name: String) =
        Device().apply {
            deviceID = id
            this.name = name
            addresses = listOf("dynamic")
        }
}
