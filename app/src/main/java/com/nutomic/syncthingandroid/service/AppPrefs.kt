package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

/** Provides preference getters. */
object AppPrefs {
    private const val TAG = "AppPrefs"
    private const val PREF_VERBOSE_LOG_DEFAULT = false

    @JvmStatic
    fun getPrefVerboseLog(context: Context?): Boolean {
        if (context == null) {
            Log.e(TAG, "getPrefVerboseLog: context == null")
            return PREF_VERBOSE_LOG_DEFAULT
        }
        return getPrefVerboseLog(PreferenceManager.getDefaultSharedPreferences(context))
    }

    @JvmStatic
    fun getPrefVerboseLog(sharedPreferences: SharedPreferences?): Boolean {
        if (sharedPreferences == null) {
            Log.e(TAG, "getPrefVerboseLog: sharedPreferences == null")
            return PREF_VERBOSE_LOG_DEFAULT
        }
        return sharedPreferences.getBoolean(Constants.PREF_VERBOSE_LOG, PREF_VERBOSE_LOG_DEFAULT)
    }

    @JvmStatic
    fun getStartServiceOnBoot(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)
}
