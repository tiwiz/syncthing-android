package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

// The backup zip of SyncthingService.exportConfig and importConfig, and the SharedPreferences it
// carries.

private const val TAG = "SyncthingService"

/** Preferences that are no longer used, left over from previous versions of the app. */
private val DEPRECATED_PREFS =
    setOf(
        "first_start",
        "advanced_folder_picker",
        "backup_folder_name",
        "bind_network",
        "log_to_file",
        "notification_type",
        "notify_crashes",
        "suggest_new_folder_root",
        "use_legacy_hashing",
        "pref_current_language",
        "restartOnWakeup",
        "wakelock_while_binary_running",
        "use_root",
        "important_news_shown_version",
    )

/** Cached information which is not available in the settings. */
private val CACHE_PREFS =
    setOf(
        Constants.PREF_APP_START_COUNTER,
        Constants.PREF_BTNSTATE_FORCE_START_STOP,
        Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
        Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID,
        Constants.PREF_LAST_BINARY_VERSION,
        Constants.PREF_LOCAL_DEVICE_ID,
        Constants.PREF_LAST_RUN_TIME,
        Constants.PREF_VERSIONING_CLEANUP_INTERVALS,
    )

/**
 * Writes the config, keys, index database and [preferences] to [targetZip], encrypted when the user
 * set a backup password; false if anything failed.
 */
internal fun exportBackup(
    context: Context,
    preferences: SharedPreferences,
    targetZip: File,
): Boolean {
    checkNotNull(targetZip.parentFile).mkdirs()
    val sharedPreferencesFile = Constants.getSharedPrefsFile(context)
    var success = exportSharedPrefs(sharedPreferencesFile, preferences)
    val includePaths =
        listOf(
            Constants.getConfigFile(context),
            Constants.getPrivateKeyFile(context),
            Constants.getPublicKeyFile(context),
            Constants.getHttpsCertFile(context),
            Constants.getHttpsKeyFile(context),
            sharedPreferencesFile,
            Constants.getIndexDbFolder(context),
        )
    val zipEncryptionPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "").orEmpty()
    if (writeBackupZip(targetZip, includePaths, zipEncryptionPassword)) {
        if (sharedPreferencesFile.exists()) {
            sharedPreferencesFile.delete()
        }
    } else {
        success = false
    }
    return success
}

/**
 * Replaces the index database, config, keys and [preferences] with the ones in [zipFile]; false if
 * a file is missing afterwards or anything failed. Syncthing must be stopped.
 */
internal fun restoreBackup(
    context: Context,
    preferences: SharedPreferences,
    zipFile: ZipFile,
    logV: (String) -> Unit,
): Boolean {
    var success = true
    val databasePath = Constants.getIndexDbFolder(context)
    if (databasePath.exists()) {
        Log.d(TAG, "importConfig: Clearing index database")
        try {
            FileUtils.deleteDirectoryRecursively(databasePath)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to delete directory '${databasePath.absolutePath}'$e")
        }
    }
    try {
        zipFile.extractAll(context.filesDir.absolutePath)
    } catch (e: IOException) {
        Log.e(TAG, "importConfig: Failed to extract zip, " + e.message)
        success = false
    }
    val checkPaths =
        listOf(
            Constants.getConfigFile(context),
            Constants.getPrivateKeyFile(context),
            Constants.getPublicKeyFile(context),
            Constants.getHttpsCertFile(context),
            Constants.getHttpsKeyFile(context),
            Constants.getSharedPrefsFile(context),
        )
    for (checkPath in checkPaths) {
        if (!checkPath.exists()) {
            Log.e(TAG, "importConfig: Missing file after extraction [${checkPath.name}]")
            success = false
        }
    }
    val sharedPreferencesFile = Constants.getSharedPrefsFile(context)
    if (sharedPreferencesFile.exists()) {
        Log.d(TAG, "importConfig: Importing shared preferences")
        success = success && importSharedPrefs(sharedPreferencesFile, preferences, logV)
        sharedPreferencesFile.delete()
    }
    try {
        cleanupImportedFolderDatabases(context)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // A broken imported config must not fail the import.
        Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e)
    }
    return success
}

/** Resets the database if the imported folders are not all on this device. */
private fun cleanupImportedFolderDatabases(context: Context) {
    val configXml = ConfigXml(context)
    try {
        configXml.loadConfig()
    } catch (_: ConfigXml.OpenConfigException) {
        Log.w(TAG, "importConfig: Unable to parse imported config for DB cleanup")
        return
    }
    if (importedFoldersNeedDatabaseReset(configXml.folders)) {
        context.appDependencies().let {
            SyncthingRunnable(
                    context,
                    SyncthingRunnable.Command.RESET_DATABASE,
                    it.preferences(),
                    it.notificationHandler(),
                )
                .run()
        }
    }
}

/** Serializes all [preferences] to [file]; false if that failed. */
internal fun exportSharedPrefs(file: File, preferences: SharedPreferences): Boolean =
    try {
        FileOutputStream(file).use { fileOutputStream ->
            ObjectOutputStream(fileOutputStream).use { objectOutputStream ->
                objectOutputStream.writeObject(preferences.all)
                objectOutputStream.flush()
                fileOutputStream.flush()
            }
        }
        true
    } catch (e: IOException) {
        Log.e(TAG, "exportConfig: Failed to export SharedPreferences #1", e)
        false
    }

/**
 * Zips the existing [includePaths] into a fresh [targetZip], AES-256 encrypted when [password] is
 * set; false if that failed.
 */
internal fun writeBackupZip(targetZip: File, includePaths: List<File>, password: String): Boolean {
    try {
        // Delete an existing zip to create a fresh archive instead of appending.
        if (targetZip.exists()) {
            targetZip.delete()
        }
        val parameters =
            ZipParameters().apply {
                compressionMethod = CompressionMethod.DEFLATE
                compressionLevel = CompressionLevel.NORMAL
            }
        val zipFile: ZipFile
        if (password.isEmpty()) {
            zipFile = ZipFile(targetZip)
            parameters.isEncryptFiles = false
        } else {
            zipFile = ZipFile(targetZip, password.toCharArray())
            parameters.isEncryptFiles = true
            parameters.encryptionMethod = EncryptionMethod.AES
            parameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        for (includePath in includePaths) {
            when {
                !includePath.exists() -> Unit
                includePath.isFile -> zipFile.addFile(includePath, parameters)
                includePath.isDirectory -> zipFile.addFolder(includePath, parameters)
            }
        }
        return true
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Whatever goes wrong while zipping fails the export, as before.
        Log.w(TAG, "exportConfig: Failed to export config, " + e.message)
        return false
    }
}

/**
 * Opens the backup zip and checks it: encrypted when [password] is set, with the config and keys
 * inside, and readable with [password] (by extracting the public key to [cacheDir]). Null if not.
 */
internal fun openBackupZip(zipFilePath: File, password: String, cacheDir: File): ZipFile? {
    try {
        val zipFile =
            if (password.isEmpty()) {
                ZipFile(zipFilePath)
            } else {
                ZipFile(zipFilePath, password.toCharArray()).also {
                    if (!it.isEncrypted) {
                        Log.e(
                            TAG,
                            "importConfig: ZIP file is not encrypted, but password was specified " +
                                "in settings screen. Try to specify an empty password temporarily.",
                        )
                        return null
                    }
                }
            }
        for (checkFile in
            listOf(Constants.CONFIG_FILE, Constants.PRIVATE_KEY_FILE, Constants.PUBLIC_KEY_FILE)) {
            if (zipFile.getFileHeader(checkFile) == null) {
                Log.e(TAG, "importConfig: Required file not found inside zip [$checkFile]")
                return null
            }
        }
        // Test if the encryption password is correct.
        zipFile.extractFile(Constants.PUBLIC_KEY_FILE, cacheDir.absolutePath)
        File(cacheDir, Constants.PUBLIC_KEY_FILE).delete()
        return zipFile
    } catch (e: ZipException) {
        Log.e(TAG, "importConfig: Failed to open zip, " + e.message)
        return null
    }
}

/**
 * Replaces [preferences] with the ones serialized in [file], except deprecated and cached ones;
 * this device's backup path and password win over the imported ones, including "unset".
 */
internal fun importSharedPrefs(
    file: File,
    preferences: SharedPreferences,
    logV: (String) -> Unit,
): Boolean =
    try {
        FileInputStream(file).use { fileInputStream ->
            ObjectInputStream(fileInputStream).use { objectInputStream ->
                val imported = objectInputStream.readObject()
                if (imported is Map<*, *>) {
                    commitImportedPrefs(imported, preferences, logV)
                } else {
                    Log.e(TAG, "importConfig: Invalid object stream")
                    true
                }
            }
        }
    } catch (e: IOException) {
        Log.e(TAG, "importConfig: Failed to import SharedPreferences #1", e)
        false
    } catch (e: ClassNotFoundException) {
        Log.e(TAG, "importConfig: Failed to import SharedPreferences #1", e)
        false
    }

private fun commitImportedPrefs(
    imported: Map<*, *>,
    preferences: SharedPreferences,
    logV: (String) -> Unit,
): Boolean {
    // Null when unset, so an unset pref is not turned into an empty string.
    val relPathToZip = preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, null)
    val backupPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, null)
    val editor = preferences.edit()
    editor.clear()
    for ((key, value) in imported) {
        val prefKey = key as String
        when (prefKey) {
            in DEPRECATED_PREFS -> logV("importConfig: Ignoring deprecated pref \"$prefKey\".")
            in CACHE_PREFS -> logV("importConfig: Ignoring cache pref \"$prefKey\".")
            else -> {
                Log.i(TAG, "importConfig: Adding pref \"$prefKey\" to commit ...")
                putImportedPref(editor, prefKey, value)
            }
        }
    }
    if (relPathToZip != null) {
        editor.putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, relPathToZip)
    } else {
        editor.remove(Constants.PREF_BACKUP_REL_PATH_TO_ZIP)
    }
    if (backupPassword != null) {
        editor.putString(Constants.PREF_BACKUP_PASSWORD, backupPassword)
    } else {
        editor.remove(Constants.PREF_BACKUP_PASSWORD)
    }
    return editor.commit()
}

/** The editor only provides typed setters. */
private fun putImportedPref(editor: SharedPreferences.Editor, prefKey: String, value: Any?) {
    when (value) {
        is Boolean -> editor.putBoolean(prefKey, value)
        is String -> editor.putString(prefKey, value)
        is Int -> editor.putInt(prefKey, value)
        is Float -> editor.putFloat(prefKey, value)
        is Long -> editor.putLong(prefKey, value)
        is Set<*> -> editor.putStringSet(prefKey, SyncthingService.asSet(value, String::class.java))
        else ->
            Log.w(
                TAG,
                "importConfig: SharedPref type ${checkNotNull(value).javaClass.name} is unknown",
            )
    }
}

/**
 * Whether one of the imported [folders] lacks its directory or its marker: then Syncthing's
 * database refers to folders that are not there, and must be reset.
 */
private fun importedFoldersNeedDatabaseReset(folders: List<Folder>): Boolean {
    for (folder in folders) {
        val folderId = folder.id
        if (folderId.isNullOrEmpty()) {
            continue
        }
        val folderPath = folder.path?.takeIf { it.isNotEmpty() }?.let(::File)
        val markerName = folder.markerName.ifEmpty { Constants.FILENAME_STFOLDER }
        if (
            folderPath == null || !folderPath.isDirectory || !File(folderPath, markerName).exists()
        ) {
            Log.i(
                TAG,
                "importConfig: Folder path or marker missing for folder id \"$folderId\". " +
                    "Resetting Syncthing database.",
            )
            return true
        }
    }
    return false
}
