@file:JvmName("UiUtil")

package com.nutomic.syncthingandroid.util

import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * Make sure that dialog is showing and activity is valid before dismissing dialog, to prevent
 * various crashes.
 */
fun dismissDialogSafe(dialog: Dialog?, activity: AppCompatActivity) {
    if (dialog == null || !dialog.isShowing || activity.isFinishing || activity.isDestroyed) {
        return
    }
    dialog.dismiss()
}

fun isRunningOnTV(context: Context): Boolean =
    context.getSystemService(UiModeManager::class.java).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION
