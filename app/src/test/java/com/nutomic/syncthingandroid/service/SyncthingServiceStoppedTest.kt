package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.drainMain
import com.nutomic.syncthingandroid.testutil.exportAndWait
import com.nutomic.syncthingandroid.testutil.importAndWait
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * [SyncthingService] while Syncthing is not running (forced off): the intents it handles by editing
 * `config.xml`, certificate changes that wait for the next start, and the parts of a backup import
 * that [BackupTest] does not reach.
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
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SyncthingServiceStoppedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private lateinit var configFile: File

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        configFile = ConfigFixture.install(context)
        prefs
            .edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_STOP)
            .commit()
    }

    /** A created service that got [intent] as its start command. */
    private fun startedWith(intent: Intent?): SyncthingService {
        val controller = Robolectric.buildService(SyncthingService::class.java, intent).create()
        controller.startCommand(0, 1)
        drainMain()
        return controller.get()
    }

    @Test
    fun `a new listener hears the current state right away`() {
        val service = StoppedSyncthingService.install()
        val states = mutableListOf<SyncthingService.State>()

        service.registerOnServiceStateChangeListener { states += it }

        assertEquals(listOf(SyncthingService.State.DISABLED), states)
    }

    @Test
    fun `the run decision is explained even before the monitor runs`() {
        val service = StoppedSyncthingService.install()

        assertEquals(
            context.getString(R.string.reason_run_condition_monitor_not_instantiated),
            service.runDecisionExplanation,
        )
    }

    @Test
    fun `a forced stop keeps Syncthing disabled`() {
        val service = startedWith(Intent())

        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        assertNull(service.api)
    }

    @Test
    fun `ignoring a device from its notification writes the config file`() {
        startedWith(
            Intent(SyncthingService.ACTION_IGNORE_DEVICE)
                .putExtra(SyncthingService.EXTRA_DEVICE_ID, NEW_ID)
                .putExtra(SyncthingService.EXTRA_DEVICE_NAME, "tablet")
                .putExtra(SyncthingService.EXTRA_DEVICE_ADDRESS, "192.0.2.9")
                .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, 7)
        )

        val config = configFile.readText()
        assertTrue(config, config.contains("<remoteIgnoredDevice"))
        assertTrue(config.contains(NEW_ID))
    }

    @Test
    fun `ignoring a folder from its notification writes the config file`() {
        startedWith(
            Intent(SyncthingService.ACTION_IGNORE_FOLDER)
                .putExtra(SyncthingService.EXTRA_DEVICE_ID, ConfigFixture.REMOTE_DEVICE_ID)
                .putExtra(SyncthingService.EXTRA_FOLDER_ID, "music")
                .putExtra(SyncthingService.EXTRA_FOLDER_LABEL, "Music")
                .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, 8)
        )

        val config = configFile.readText()
        assertTrue(config, config.contains("<ignoredFolder"))
        assertTrue(config.contains("\"music\""))
    }

    @Test
    fun `a new certificate waits for the next start when Syncthing is off`() {
        val service = startedWith(Intent())
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        certFile.writeText("old cert")
        keyFile.writeText("old key")
        val results = mutableListOf<Pair<SyncthingService.HttpsCertReplaceResult, String?>>()

        service.replaceHttpsCertificate(
            Fixtures.bytes("certs/rsa-cert.pem"),
            Fixtures.bytes("certs/rsa-key.pem"),
        ) { result, detail ->
            results += result to detail
        }
        drainMain()

        assertEquals(
            listOf(SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START to null),
            results,
        )
        assertTrue(certFile.readBytes().contentEquals(Fixtures.bytes("certs/rsa-cert.pem")))
        assertTrue(keyFile.readBytes().contentEquals(Fixtures.bytes("certs/rsa-key.pem")))
        assertFalse(File(certFile.path + ".bak").exists())
        assertFalse(File(keyFile.path + ".bak").exists())
        assertFalse(File(certFile.path + ".tmp").exists())
    }

    @Test
    fun `resetting the certificate removes it when Syncthing is off`() {
        val service = startedWith(Intent())
        val certFile = Constants.getHttpsCertFile(context)
        val keyFile = Constants.getHttpsKeyFile(context)
        certFile.writeText("old cert")
        keyFile.writeText("old key")
        val results = mutableListOf<SyncthingService.HttpsCertReplaceResult>()

        service.resetHttpsCertificate { result, _ -> results += result }
        drainMain()

        assertEquals(listOf(SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START), results)
        assertFalse(certFile.exists())
        assertFalse(keyFile.exists())
        assertFalse(File(certFile.path + ".bak").exists())
    }

    @Test
    fun `import without a backup zip fails`() {
        val service = StoppedSyncthingService.install()

        assertFalse(service.importAndWait())
    }

    @Test
    fun `import with a password fails on a zip without encryption`() {
        val service = StoppedSyncthingService.install()
        writeFakeKeys()
        assertTrue(service.exportAndWait())
        prefs.edit().putString(Constants.PREF_BACKUP_PASSWORD, "secret").commit()

        assertFalse(service.importAndWait())
    }

    @Test
    fun `an encrypted backup imports with its password`() {
        val service = StoppedSyncthingService.install()
        writeFakeKeys()
        prefs.edit().putString(Constants.PREF_BACKUP_PASSWORD, "secret").commit()
        assertTrue(service.exportAndWait())
        Constants.getPublicKeyFile(context).delete()

        assertTrue(service.importAndWait())
        assertEquals("fake cert.pem", Constants.getPublicKeyFile(context).readText())
    }

    @Test
    fun `import skips deprecated and cached preferences and keeps the others`() {
        val service = StoppedSyncthingService.install()
        writeFakeKeys()
        prefs
            .edit()
            .putBoolean("first_start", true)
            .putInt(Constants.PREF_APP_START_COUNTER, 9)
            .putStringSet(Constants.PREF_WIFI_SSID_WHITELIST, setOf("home", "office"))
            .putLong("some_long", 5L)
            .putFloat("some_float", 1.5f)
            .commit()
        assertTrue(service.exportAndWait())
        prefs.edit().clear().commit()

        assertTrue(service.importAndWait())

        assertFalse(prefs.contains("first_start"))
        assertFalse(prefs.contains(Constants.PREF_APP_START_COUNTER))
        assertEquals(
            setOf("home", "office"),
            prefs.getStringSet(Constants.PREF_WIFI_SSID_WHITELIST, null),
        )
        assertEquals(5L, prefs.getLong("some_long", 0))
        assertEquals(1.5f, prefs.getFloat("some_float", 0f), 0f)
    }

    @Test
    fun `asSet casts every element`() {
        assertEquals(
            setOf("a", "b"),
            SyncthingService.asSet<String>(setOf("a", "b"), String::class.java),
        )
        assertNull(SyncthingService.asSet<String>(null, String::class.java))
    }

    private fun writeFakeKeys() {
        listOf(
                Constants.getPrivateKeyFile(context),
                Constants.getPublicKeyFile(context),
                Constants.getHttpsCertFile(context),
                Constants.getHttpsKeyFile(context),
            )
            .forEach { it.writeText("fake ${it.name}") }
    }

    private companion object {
        const val NEW_ID = "AIR6LPZ-7K4PTTV-UXQSMUU-CPQ5YWH-OEDFIIQ-JUG777G-2YQXXR5-YD6AWQR"
    }
}
