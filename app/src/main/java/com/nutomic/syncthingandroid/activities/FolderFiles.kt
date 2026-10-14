package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.SafUtils
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// What the folder screen does on disk for a new folder, and the id it proposes.

private const val TAG = "FolderActivity"

/**
 * Name of the file created in the marker directory: some manufacturers' cleaning routines silently
 * wipe out empty directories like the marker directory.
 */
private const val DO_NOT_DELETE_FILE_NAME = "DO_NOT_DELETE"

private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val ID_LENGTH = 10
private const val ID_DASH_AT = 5

/** A random folder id like "abcde-fghij". */
internal fun generateRandomFolderId(random: Random = Random.Default): String = buildString {
    for (i in 0 until ID_LENGTH) {
        if (i == ID_DASH_AT) append("-")
        append(ID_CHARS[random.nextInt(ID_CHARS.length)])
    }
}

/**
 * Creates the ".stfolder" marker and ".stversions/.nomedia" (keeps the gallery clean) in a new
 * folder. Syncthing normally creates the marker, but cannot when it only has read access, e.g. for
 * a send-only folder. Through SAF when [uriFolderRoot] is known, else through [absolutePath] (the
 * built-in folder picker only returns a path).
 */
internal fun preCreateFolderStruct(context: Context, uriFolderRoot: Uri?, absolutePath: String) {
    val markerName = Constants.FILENAME_STFOLDER
    if (uriFolderRoot == null) {
        Log.w(TAG, "preCreateFolderStruct: uriFolderRoot == null. Using absolute path.")
        try {
            val marker = File(absolutePath, markerName).apply { mkdirs() }
            val doNotDelete = File(marker, DO_NOT_DELETE_FILE_NAME)
            if (doNotDelete.createNewFile()) {
                doNotDelete.writeText(DO_NOT_DELETE_FILE_NAME)
            }
            val versions = File(absolutePath, Constants.FOLDER_NAME_STVERSIONS).apply { mkdirs() }
            File(versions, ".nomedia").createNewFile()
        } catch (e: IOException) {
            Log.e(TAG, "preCreateFolderStruct: Failed to create using absolute path.", e)
        }
        return
    }
    // A DocumentFile on the SAF tree we have write access to.
    val folder = DocumentFile.fromTreeUri(context, uriFolderRoot)
    SafUtils.safCreateDirectory(folder, markerName)?.let {
        SafUtils.safCreateFile(context, it, "$DO_NOT_DELETE_FILE_NAME.txt", DO_NOT_DELETE_FILE_NAME)
    }
    SafUtils.safCreateDirectory(folder, Constants.FOLDER_NAME_STVERSIONS)?.let {
        SafUtils.safCreateFile(context, it, ".nomedia", "")
    }
}

/** Prepares a new folder on disk, then [add]s it to the config, off the main thread. */
internal suspend fun addFolderWithStruct(
    context: Context,
    uriFolderRoot: Uri?,
    absolutePath: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    add: () -> Unit,
) =
    withContext(ioDispatcher) {
        preCreateFolderStruct(context, uriFolderRoot, absolutePath)
        add()
    }
