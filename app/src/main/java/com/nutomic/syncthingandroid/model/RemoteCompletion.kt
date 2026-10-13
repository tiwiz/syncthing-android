package com.nutomic.syncthingandroid.model

import android.text.TextUtils
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.AbstractMap.SimpleEntry
import kotlin.math.floor

/**
 * Caches remote folder and device synchronization completion indicators defined in
 * [RemoteCompletionInfo], according to syncthing's REST "/completion" JSON result schema.
 * Completion model of syncthing's web UI is completion[deviceId][folderId].
 */
class RemoteCompletion(private val enableVerboseLog: Boolean) {

    /** Guarded by itself. */
    private val deviceFolderMap =
        HashMap<String?, Map.Entry<Connection, HashMap<String?, RemoteCompletionInfo>>>()

    /** Removes a folder from the cache model (from the first device sharing it). */
    private fun removeFolder(folderId: String?) {
        synchronized(deviceFolderMap) {
            for (folderMapEntry in deviceFolderMap.values) {
                val folderMap = folderMapEntry.value
                if (folderMap.containsKey(folderId)) {
                    folderMap.remove(folderId)
                    break
                }
            }
        }
    }

    /** Updates device and folder information in the cache model after a config update. */
    fun updateFromConfig(newDevices: List<Device>, newFolders: List<Folder>) {
        synchronized(deviceFolderMap) {
            // Handle devices that were removed from the config.
            val removedDevices =
                deviceFolderMap.keys.filter { id -> newDevices.none { it.deviceID == id } }
            for (deviceId in removedDevices) {
                logV(
                    "updateFromConfig: Remove device '${getShortenedDeviceId(deviceId)}' from cache model"
                )
                deviceFolderMap.remove(deviceId)
            }
            // Handle devices that were added to the config.
            for (device in newDevices) {
                if (!deviceFolderMap.containsKey(device.deviceID)) {
                    logV(
                        "updateFromConfig: Add device '${getShortenedDeviceId(device.deviceID)}' to cache model"
                    )
                    deviceFolderMap[device.deviceID] = newDeviceEntry()
                }
            }
            // Handle folders that were removed from the config.
            val removedFolders =
                deviceFolderMap.values.flatMap { device ->
                    device.value.keys.filter { id -> newFolders.none { it.id == id } }
                }
            for (folderId in removedFolders) {
                logV("updateFromConfig: Remove folder '$folderId' from cache model")
                removeFolder(folderId)
            }
            // Handle folders that were added to the config.
            for (folder in newFolders) {
                for (device in newDevices) {
                    if (folder.getDevice(device.deviceID) == null) {
                        continue
                    }
                    // folder is shared with device.
                    val folderMap = deviceFolderMap.getValue(device.deviceID).value
                    if (!folderMap.containsKey(folder.id)) {
                        logV(
                            "updateFromConfig: Add folder '${folder.id}' shared with device '" +
                                "${getShortenedDeviceId(device.deviceID)}' to cache model."
                        )
                        folderMap[folder.id] = RemoteCompletionInfo()
                    }
                }
            }
        }
    }

    /**
     * Remote device sync completion percentage across all connected devices, or -1 if sync
     * completion is not applicable.
     */
    val totalDeviceCompletion: Int
        get() =
            synchronized(deviceFolderMap) {
                val connected = deviceFolderMap.values.filter { it.key.connected }
                if (connected.isEmpty()) {
                    return -1
                }
                averageInProgress(connected.flatMap { it.value.values })
            }

    /** Remote device sync completion percentage across all folders shared with the device. */
    fun getDeviceCompletion(deviceId: String?): Int =
        synchronized(deviceFolderMap) {
            val device = deviceFolderMap[deviceId]
            if (device == null) {
                logV("getDeviceCompletion: Cache miss for deviceId=[$deviceId]")
                return COMPLETE
            }
            averageInProgress(device.value.values)
        }

    fun getDeviceNeedBytes(deviceId: String?): Double =
        synchronized(deviceFolderMap) {
            val device = deviceFolderMap[deviceId]
            if (device == null) {
                logV("getDeviceNeedBytes: Cache miss for deviceId=[$deviceId]")
                return 0.0
            }
            device.value.values.sumOf { it.needBytes }
        }

    /** Sets completionInfo within the completion[deviceId][folderId] model. */
    fun setCompletionInfo(
        deviceId: String?,
        folderId: String?,
        completionInfo: RemoteCompletionInfo,
    ) {
        synchronized(deviceFolderMap) {
            // Add device parent node if it does not exist.
            val device = deviceFolderMap.getOrPut(deviceId, ::newDeviceEntry)
            logV(
                "setCompletionInfo: Storing ${completionInfo.completion}% for folder \"$folderId\"" +
                    " at device \"${getShortenedDeviceId(deviceId)}\"."
            )
            // Add folder or update existing folder entry.
            device.value[folderId] = completionInfo
        }
    }

    /** Remote device status, as a copy. */
    fun getDeviceStatus(deviceId: String?): Connection =
        synchronized(deviceFolderMap) {
            val device = deviceFolderMap[deviceId] ?: return Connection()
            deepCopy(device.key, CONNECTION_TYPE)
        }

    val onlineDeviceCount: Int
        get() = synchronized(deviceFolderMap) { deviceFolderMap.values.count { it.key.connected } }

    /** Stores remote device status for later when we need info for the UI. */
    fun setDeviceStatus(deviceId: String?, connection: Connection) {
        synchronized(deviceFolderMap) {
            // Add device parent node if it does not exist.
            val device = deviceFolderMap.getOrPut(deviceId, ::newDeviceEntry)
            // Update device status information.
            deviceFolderMap[deviceId] =
                SimpleEntry(
                    deepCopy(connection, CONNECTION_TYPE),
                    deepCopy(device.value, FOLDER_MAP_TYPE),
                )
        }
    }

    /** The first characters of the device ID for logging purposes. */
    fun getShortenedDeviceId(deviceId: String?): String =
        if (TextUtils.isEmpty(deviceId)) "" else deviceId.orEmpty().substring(0, SHORT_ID_LENGTH)

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    private companion object {
        const val TAG = "RemoteCompletion"
        val CONNECTION_TYPE = object : TypeToken<Connection>() {}
        val FOLDER_MAP_TYPE = object : TypeToken<HashMap<String?, RemoteCompletionInfo>>() {}

        fun newDeviceEntry(): Map.Entry<Connection, HashMap<String?, RemoteCompletionInfo>> =
            SimpleEntry(Connection(), HashMap())

        /**
         * Average completion of the folders still in progress, rounded down; 100 when there are
         * none. Syncthing's web UI considers remote folders at 0% and 100% as up to date.
         */
        fun averageInProgress(folders: Collection<RemoteCompletionInfo>): Int {
            val inProgress =
                folders
                    .map { it.completion.coerceIn(0.0, COMPLETE_PERCENT) }
                    .filter { it != 0.0 && it != COMPLETE_PERCENT }
            return if (inProgress.isEmpty()) COMPLETE
            else floor(inProgress.sum() / inProgress.size).toInt().coerceIn(0, COMPLETE)
        }

        /** A deep copy through Gson; only works with objects Gson can convert. */
        fun <T> deepCopy(value: T, type: TypeToken<T>): T =
            Gson().run { fromJson(toJson(value, type.type), type.type) }
    }
}
