package com.nutomic.syncthingandroid.service

import android.content.SharedPreferences
import android.util.Log
import com.nutomic.syncthingandroid.model.Config
import com.nutomic.syncthingandroid.util.ConfigXml

// The folders' and devices' own sync conditions, applied to the config RestApi holds while
// Syncthing runs, or to config.xml while it is stopped.

// The log tags of the classes these come from.
private const val REST_API_TAG = "RestApi"
private const val SERVICE_TAG = "SyncthingService"

/**
 * Pauses or resumes the folders and devices that have their own sync conditions, by those
 * conditions. Returns whether the config changed, or null if it is not ready yet.
 */
internal fun applyObjectRunConditions(
    config: Config,
    preferences: SharedPreferences,
    runConditionMonitor: RunConditionMonitor,
    logV: (String) -> Unit,
): Boolean? {
    var configChanged = false
    val folders = config.folders
    if (folders == null) {
        Log.d(REST_API_TAG, "applyCustomRunConditions: mConfig.folders is not ready yet.")
        return null
    }
    for (folder in folders) {
        val prefix = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
        if (!preferences.getBoolean(Constants.dynPrefObjectCustomSyncConditions(prefix), false))
            continue
        val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
        logV("applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) "1" else "0"}")
        if (folder.paused != !syncConditionsMet) {
            folder.paused = !syncConditionsMet
            Log.d(
                REST_API_TAG,
                "applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) ">1" else ">0"}",
            )
            configChanged = true
        }
    }
    val devices = config.devices
    if (devices == null) {
        Log.d(REST_API_TAG, "applyCustomRunConditions: mConfig.devices is not ready yet.")
        return null
    }
    for (device in devices) {
        val prefix = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
        if (!preferences.getBoolean(Constants.dynPrefObjectCustomSyncConditions(prefix), false))
            continue
        val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
        logV("applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) "1" else "0"}")
        if (device.paused != !syncConditionsMet) {
            device.paused = !syncConditionsMet
            Log.d(
                REST_API_TAG,
                "applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) ">1" else ">0"}",
            )
            configChanged = true
        }
    }
    return configChanged
}

/**
 * Pauses or resumes, in config.xml, the folders and devices that have their own sync conditions, by
 * those conditions. Returns whether the config changed; the caller saves it.
 */
internal fun applyObjectRunConditions(
    configXml: ConfigXml,
    preferences: SharedPreferences,
    runConditionMonitor: RunConditionMonitor,
    logV: (String) -> Unit,
): Boolean {
    var configChanged = false
    for (folder in configXml.folders) {
        val prefix = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
        if (!preferences.getBoolean(Constants.dynPrefObjectCustomSyncConditions(prefix), false))
            continue
        val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
        logV("applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) "1" else "0"}")
        if (folder.paused != !syncConditionsMet) {
            configXml.setFolderPause(folder.id, !syncConditionsMet)
            Log.d(
                SERVICE_TAG,
                "applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) ">1" else ">0"}",
            )
            configChanged = true
        }
    }
    for (device in configXml.getDevices(false)) {
        val prefix = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
        if (!preferences.getBoolean(Constants.dynPrefObjectCustomSyncConditions(prefix), false))
            continue
        val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
        logV("applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) "1" else "0"}")
        if (device.paused != !syncConditionsMet) {
            configXml.setDevicePause(device.deviceID, !syncConditionsMet)
            Log.d(
                SERVICE_TAG,
                "applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) ">1" else ">0"}",
            )
            configChanged = true
        }
    }
    return configChanged
}
