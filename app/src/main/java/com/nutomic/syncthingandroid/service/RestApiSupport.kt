package com.nutomic.syncthingandroid.service

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.model.Config
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Connections
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.PendingDevice
import com.nutomic.syncthingandroid.model.PendingFolder

// RestApi's helpers that need no REST requests of their own.

private const val TAG = "RestApi"

/** A deep copy through Gson; only works with objects Gson can convert. */
internal fun <T> deepCopyWithGson(value: T, type: java.lang.reflect.Type): T =
    Gson().run { fromJson(toJson(value, type), type) }

/** Shows a consent notification for each device in a /rest/cluster/pending/devices answer. */
internal fun notifyPendingDevices(
    result: String?,
    gson: Gson,
    notificationHandler: NotificationHandler,
) {
    if (result == null) {
        Log.e(TAG, "ORCC: URI_PENDING_DEVICES, result == null")
        return
    }
    for ((resultDeviceId, value) in JsonParser.parseString(result).asJsonObject.entrySet()) {
        val pendingDevice = gson.fromJson(value, PendingDevice::class.java)
        @Suppress("SENSELESS_COMPARISON") // Gson leaves a field missing from the JSON null.
        if (pendingDevice.time == null) continue
        Log.d(TAG, "ORCC: resultDeviceId = $resultDeviceId('${pendingDevice.name}')")
        notificationHandler.showDeviceConnectNotification(
            resultDeviceId,
            pendingDevice.name,
            pendingDevice.address,
        )
    }
}

/**
 * Shows a consent notification for each folder offer in a /rest/cluster/pending/folders answer,
 * naming the offering device from [devices] and telling new folders apart from [folders].
 */
internal fun notifyPendingFolders(
    result: String?,
    gson: Gson,
    notificationHandler: NotificationHandler,
    devices: List<Device>,
    folders: List<Folder>,
) {
    if (result == null) {
        Log.e(TAG, "ORCC: URI_PENDING_FOLDERS, result == null")
        return
    }
    for ((resultFolderId, folderValue) in JsonParser.parseString(result).asJsonObject.entrySet()) {
        val offeredBy = folderValue.asJsonObject.get("offeredBy").asJsonObject
        for ((offeredByDeviceId, offer) in offeredBy.entrySet()) {
            val pendingFolder = gson.fromJson(offer, PendingFolder::class.java)
            Log.d(TAG, "ORCC: resultFolderId = $resultFolderId('${pendingFolder.label}')")
            val matchingDevice = devices.first { checkNotNull(it.deviceID) == offeredByDeviceId }
            val isNewFolder = folders.none { checkNotNull(it.id) == resultFolderId }
            notificationHandler.showFolderShareNotification(
                offeredByDeviceId,
                matchingDevice.displayName,
                resultFolderId,
                pendingFolder.label,
                checkNotNull(pendingFolder.receiveEncrypted),
                checkNotNull(pendingFolder.remoteEncrypted),
                isNewFolder,
            )
        }
    }
}

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
        Log.d(TAG, "applyCustomRunConditions: mConfig.folders is not ready yet.")
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
                TAG,
                "applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) ">1" else ">0"}",
            )
            configChanged = true
        }
    }
    val devices = config.devices
    if (devices == null) {
        Log.d(TAG, "applyCustomRunConditions: mConfig.devices is not ready yet.")
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
                TAG,
                "applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) ">1" else ">0"}",
            )
            configChanged = true
        }
    }
    return configChanged
}

/** Transfer rates per device and in total, from successive /rest/system/connections answers. */
internal class ConnectionStats {
    /** The last answer the rates were calculated for, or null before the first. */
    private var previousConnections: Connections? = null
    private var previousConnectionTime = 0L

    val total: Connection
        get() =
            previousConnections?.let { deepCopyWithGson(it.total, Connection::class.java) }
                ?: Connection()

    /**
     * Calculates the transfer rates of [connections] against the previous answer. Answers within
     * the REST polling interval of the previous one are left without rates, as the Java original
     * did.
     */
    fun calculate(connections: Connections) {
        val now = System.currentTimeMillis()
        val msElapsed = now - previousConnectionTime
        if (msElapsed < Constants.REST_UPDATE_INTERVAL) {
            return
        }
        previousConnectionTime = now
        val previous = previousConnections
        for ((deviceId, connection) in checkNotNull(connections.connections)) {
            connection.setTransferRate(
                previous?.connections?.get(deviceId) ?: Connection(),
                msElapsed,
            )
        }
        checkNotNull(connections.total).setTransferRate(previous?.total ?: Connection(), msElapsed)
        previousConnections = connections
    }
}
