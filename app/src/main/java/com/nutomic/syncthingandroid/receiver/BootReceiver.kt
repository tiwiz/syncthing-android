package com.nutomic.syncthingandroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.SyncthingService

class BootReceiver : BroadcastReceiver() {

    /**
     * Starts the service after boot or an app update, if the user wants it. For testing purposes:
     * adb root & adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = checkNotNull(intent.action)
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        // Check if we should (re)start now.
        if (!AppPrefs.getStartServiceOnBoot(context)) {
            return
        }
        startServiceCompat(context)
    }

    companion object {
        /**
         * Workaround for starting service from background on Android 8+.
         *
         * https://stackoverflow.com/a/44505719/1837158
         */
        @JvmStatic
        fun startServiceCompat(context: Context) {
            val intent = Intent(context, SyncthingService::class.java)
            context.startForegroundService(intent)
        }
    }
}
