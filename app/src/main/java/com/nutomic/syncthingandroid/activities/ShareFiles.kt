package com.nutomic.syncthingandroid.activities

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.nutomic.syncthingandroid.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The files the share screen copies: their names, and the copy itself.
//
// getDisplayNameForUri and displayNameFromContentResolver are taken from ownCloud Android
// (https://github.com/owncloud/android/blob/master/owncloudApp/src/main/java/com/owncloud/android/utils/UriUtils.java).

private const val TAG = "ShareActivity"

/** The file name for a shared [uri], or a generated one ("file_name_template") without one. */
internal fun displayNameFor(context: Context, uri: Uri): String =
    getDisplayNameForUri(context, uri)
        ?: context.getString(
            R.string.file_name_template,
            DateFormat.getDateTimeInstance().format(Date(System.currentTimeMillis())),
        )

private fun getDisplayNameForUri(context: Context, uri: Uri): String? {
    var displayName: String?
    if (ContentResolver.SCHEME_CONTENT != uri.scheme) {
        displayName = uri.lastPathSegment
    } else {
        // Last chance to have a name: the URI itself.
        var name =
            displayNameFromContentResolver(context, uri)
                ?: uri.lastPathSegment.orEmpty().replace(Regex("\\s"), "")
        // Add the best possible extension.
        val index = name.lastIndexOf(".")
        if (
            index == -1 ||
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(index + 1)) ==
                    null
        ) {
            val extension =
                MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(context.contentResolver.getType(uri))
            if (extension != null) {
                name += ".$extension"
            }
        }
        displayName = name
    }
    // Replace path separator characters to avoid inconsistent paths.
    return displayName?.replace("/", "-")
}

/** The name from a content:// URI's provider. */
private fun displayNameFromContentResolver(context: Context, uri: Uri): String? {
    val mimeType = context.contentResolver.getType(uri) ?: return null
    val column =
        when {
            mimeType.startsWith("image/") -> MediaStore.Images.ImageColumns.DISPLAY_NAME
            mimeType.startsWith("video/") -> MediaStore.Video.VideoColumns.DISPLAY_NAME
            mimeType.startsWith("audio/") -> MediaStore.Audio.AudioColumns.DISPLAY_NAME
            else -> MediaStore.Files.FileColumns.DISPLAY_NAME
        }
    return try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndexOrThrow(column))
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Providers fail in many ways; the caller then makes up a name.
        Log.e(TAG, "Could not retrieve display name for $uri", e)
        null
    }
}

/** How a copy went: how many files were copied, how many kept, and whether one failed. */
internal class CopyResult(val copied: Int, val ignored: Int, val isError: Boolean)

/**
 * Copies each shared file to [directory] under its name. Existing files are kept unless
 * [allowOverwrite].
 */
internal suspend fun copySharedFiles(
    context: Context,
    files: Map<Uri, String>,
    directory: File,
    allowOverwrite: Boolean,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): CopyResult =
    withContext(ioDispatcher) {
        var copied = 0
        var ignored = 0
        var isError = false
        for ((uri, name) in files) {
            val outFile = File(directory, name)
            if (outFile.isFile && !allowOverwrite) {
                ignored++
                continue
            }
            try {
                checkNotNull(context.contentResolver.openInputStream(uri)) { "No stream for $uri" }
                    .use { input -> outFile.outputStream().use { input.copyTo(it) } }
                copied++
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "Can't find input file \"$uri\" to copy", e)
                isError = true
            } catch (e: IOException) {
                Log.e(TAG, "IO exception during file \"$uri\" sharing", e)
                isError = true
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IO exception during file \"$uri\" sharing", e)
                isError = true
            }
        }
        CopyResult(copied, ignored, isError)
    }
