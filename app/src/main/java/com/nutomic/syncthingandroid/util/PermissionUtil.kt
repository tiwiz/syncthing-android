package com.nutomic.syncthingandroid.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.R

object PermissionUtil {
    private const val TAG = "PermissionUtil"

    /** Write access to external storage: "All files access" from Android 11. */
    @JvmStatic fun haveStoragePermission(): Boolean = Environment.isExternalStorageManager()

    @JvmStatic
    fun requestStoragePermission(activity: Activity) {
        val intent =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData("package:${activity.packageName}".toUri())
        try {
            if (intent.resolveActivity(activity.packageManager) != null) {
                // Launch "Allow all files access?" dialog.
                activity.startActivity(intent)
                return
            }
            Log.w(TAG, "Request all files access not supported")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Request all files access not supported", e)
        }
        // Some devices don't support this request.
        Toast.makeText(activity, R.string.dialog_all_files_access_not_supported, Toast.LENGTH_LONG)
            .show()
    }
}
