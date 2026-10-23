package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.exportAndWait
import com.nutomic.syncthingandroid.testutil.importAndWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Exports the config and keys of a stopped [SyncthingService] to the backup zip and imports them
 * back. The keys are fake: export and import only copy them.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class BackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private lateinit var service: SyncthingService

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        listOf(
                Constants.getPrivateKeyFile(context),
                Constants.getPublicKeyFile(context),
                Constants.getHttpsCertFile(context),
                Constants.getHttpsKeyFile(context),
            )
            .forEach { it.writeText("fake ${it.name}") }
        service = StoppedSyncthingService.install()
    }

    @Test
    fun `export then import restores the config and the preferences`() {
        prefs.edit().putBoolean(Constants.PREF_START_SERVICE_ON_BOOT, true).commit()

        assertTrue(service.exportAndWait())
        prefs.edit().putBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false).commit()
        Constants.getPrivateKeyFile(context).delete()
        assertTrue(service.importAndWait())

        assertTrue(prefs.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false))
        assertEquals("fake key.pem", Constants.getPrivateKeyFile(context).readText())
    }

    @Test
    fun `import keeps an unset backup path unset`() {
        assertTrue(service.exportAndWait())
        assertTrue(service.importAndWait())

        assertFalse(prefs.contains(Constants.PREF_BACKUP_REL_PATH_TO_ZIP))
        assertFalse(prefs.contains(Constants.PREF_BACKUP_PASSWORD))
    }

    @Test
    fun `import does not take the backup path from the zip`() {
        // The default path, set explicitly, so the zip carries the pref and import still finds it.
        prefs
            .edit()
            .putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, "backups/syncthing/config.zip")
            .commit()
        assertTrue(service.exportAndWait())
        prefs.edit().remove(Constants.PREF_BACKUP_REL_PATH_TO_ZIP).commit()

        assertTrue(service.importAndWait())

        assertFalse(prefs.contains(Constants.PREF_BACKUP_REL_PATH_TO_ZIP))
    }

    @Test
    fun `import keeps the backup path and password set on this device`() {
        prefs
            .edit()
            .putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, "backups/mine.zip")
            .putString(Constants.PREF_BACKUP_PASSWORD, "secret")
            .commit()

        assertTrue(service.exportAndWait())
        assertTrue(service.importAndWait())

        assertEquals(
            "backups/mine.zip",
            prefs.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, null),
        )
        assertEquals("secret", prefs.getString(Constants.PREF_BACKUP_PASSWORD, null))
    }
}
