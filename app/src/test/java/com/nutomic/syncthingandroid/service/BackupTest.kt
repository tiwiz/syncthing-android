package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.ShadowZipFileWithCorruptHeader
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.exportAndWait
import com.nutomic.syncthingandroid.testutil.importAndWait
import com.nutomic.syncthingandroid.testutil.registerFreeSpace
import com.nutomic.syncthingandroid.util.ConfigXml
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

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
        registerFreeSpace(context)
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

    @Test
    @Config(
        shadows =
            [
                ShadowEnvironmentWithAllFilesAccess::class,
                ShadowUtilDeviceShell::class,
                ShadowZipFileWithCorruptHeader::class,
            ],
        instrumentedPackages = ["com.nutomic.syncthingandroid.util", "net.lingala.zip4j"],
    )
    fun `an import that cannot open the zip logs why`() {
        assertTrue(service.exportAndWait())
        ShadowLog.clear()

        assertFalse(service.importAndWait())

        val logged =
            ShadowLog.getLogsForTag("SyncthingService").any { log ->
                generateSequence(log.throwable) { it.cause }
                    .any { it.message == ShadowZipFileWithCorruptHeader.MESSAGE }
            }
        assertTrue("no log entry carries the exception", logged)
    }

    @Test
    fun `an import that fails partway leaves the config untouched`() {
        assertTrue(service.exportAndWait())
        corruptEntry(backupZip(), Constants.CONFIG_FILE)
        val original = Constants.getConfigFile(context).readText()

        assertFalse(service.importAndWait())

        assertTrue("config.xml is gone", Constants.getConfigFile(context).exists())
        assertEquals(original, Constants.getConfigFile(context).readText())
        ConfigXml(context).loadConfig()
    }

    @Test
    fun `an import that does not fit in the free space extracts nothing and logs why`() {
        assertTrue(service.exportAndWait())
        val zip = RecordingZipFile(backupZip())
        val needed = zip.fileHeaders.sumOf { it.uncompressedSize }
        val original = Constants.getConfigFile(context).readText()
        ShadowLog.clear()

        assertFalse(
            "the import went ahead without the space",
            restoreBackup(context, prefs, zip, {}, availableBytes = { needed - 1 }),
        )

        assertEquals(0, zip.extractions)
        val logged =
            ShadowLog.getLogsForTag("SyncthingService").any { log ->
                "free space" in log.msg && "$needed" in log.msg
            }
        assertTrue("no log entry gives the space reason", logged)
        assertEquals(original, Constants.getConfigFile(context).readText())
    }

    @Test
    fun `an import that fits in the free space extracts the zip`() {
        assertTrue(service.exportAndWait())
        val zip = RecordingZipFile(backupZip())
        val needed = zip.fileHeaders.sumOf { it.uncompressedSize }

        assertTrue(restoreBackup(context, prefs, zip, {}, availableBytes = { needed }))

        assertEquals(1, zip.extractions)
    }

    /** Counts the calls to [extractAll], then extracts for real. */
    private class RecordingZipFile(file: File) : ZipFile(file) {
        var extractions = 0

        override fun extractAll(destinationPath: String) {
            extractions++
            super.extractAll(destinationPath)
        }
    }

    private fun backupZip() =
        File(Environment.getExternalStorageDirectory(), "backups/syncthing/config.zip")

    /** Overwrites the middle of [name]'s compressed data in [zip]; its headers stay intact. */
    private fun corruptEntry(zip: File, name: String) {
        val bytes = zip.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (true) {
            check(buffer.getInt(offset) == LOCAL_HEADER) { "$name not found in $zip" }
            val compressedSize = buffer.getInt(offset + 18)
            val nameLength = buffer.getShort(offset + 26).toInt()
            val extraLength = buffer.getShort(offset + 28).toInt()
            val entryName = String(bytes, offset + 30, nameLength)
            val data = offset + 30 + nameLength + extraLength
            if (entryName == name) {
                for (i in compressedSize / 4 until compressedSize * 3 / 4) bytes[data + i] = -1
                zip.writeBytes(bytes)
                return
            }
            offset = data + compressedSize
        }
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
    }
}
