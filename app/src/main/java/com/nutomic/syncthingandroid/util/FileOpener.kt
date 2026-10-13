package com.nutomic.syncthingandroid.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.R
import java.io.File

/** Opens files and folders in other apps. */
object FileOpener {

    private const val TAG = "FileOpener"
    private const val FILE_MANAGER_PACKAGE = "me.zhanghai.android.files"

    /** Opens a file in a compatible app, or offers the app chooser. */
    @JvmStatic
    fun openFile(context: Context, fullPathAndFilename: String) {
        val fileExtension =
            MimeTypeMap.getFileExtensionFromUrl(fullPathAndFilename.toUri().toString())
        val mimeType = FileUtils.getMimeTypeFromFileExtension(fileExtension)
        Log.v(TAG, "openFile: Detected mime type '$mimeType' for file '$fullPathAndFilename'")
        val intent =
            if (fileExtension == "apk") {
                // Requires permission in AndroidManifest.xml. We've dropped this as this is rarely
                // used, mainly during development.
                // <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
                @Suppress("DEPRECATION") Intent(Intent.ACTION_INSTALL_PACKAGE)
            } else {
                Intent(Intent.ACTION_VIEW)
            }
        val fileUri =
            FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                File(fullPathAndFilename),
            )
        intent.setDataAndType(fileUri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "openFile: ActivityNotFoundException. Falling back to app chooser...")
            intent.setDataAndType(fullPathAndFilename.toUri(), "application/*")
            try {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.open_file_with))
                )
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Whatever the chooser fails with, tell the user no app can open the file.
                Log.e(TAG, "openFile:", e)
                Toast.makeText(context, R.string.open_file_no_compatible_app, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /** Opens a folder in a compatible file manager app, or suggests installing one. */
    @JvmStatic
    fun openFolder(context: Context, folderPath: String) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            Toast.makeText(
                    context,
                    context.getString(R.string.state_error_message, "Invalid folder path"),
                    Toast.LENGTH_LONG,
                )
                .show()
            return
        }
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(folder), "resource/folder")
                .putExtra("org.openintents.extra.ABSOLUTE_PATH", folderPath)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
        if (tryStart(context, intent, "stage #1")) {
            return
        }
        // Fall back to the document URI and directory MIME type.
        val documentUri =
            DocumentsContract.buildDocumentUri(
                FileUtils.EXTERNAL_STORAGE_AUTHORITY,
                SafUtils.getDocumentIdFromPath(folderPath),
            )
        intent.setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
        if (!tryStart(context, intent, "stage #2")) {
            suggestFileManagerApp(context)
        }
    }

    /** Starts [intent]; false if no app can handle it or it may not be started. */
    private fun tryStart(context: Context, intent: Intent, stage: String): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "openFolder: No compatible file manager app found ($stage)", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "openFolder: File manager app has insufficient permissions ($stage)", e)
            false
        }

    private fun suggestFileManagerApp(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.suggest_file_manager_app_dialog_title)
            .setMessage(R.string.suggest_file_manager_app_dialog_text)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "market://details?id=$FILE_MANAGER_PACKAGE".toUri(),
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://play.google.com/store/apps/details?id=$FILE_MANAGER_PACKAGE"
                                .toUri(),
                        )
                    )
                }
            }
            .setNegativeButton(R.string.no) { _, _ -> }
            .show()
    }
}
