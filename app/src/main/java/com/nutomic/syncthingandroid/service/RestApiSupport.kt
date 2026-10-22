package com.nutomic.syncthingandroid.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

/**
 * The config to post: the [model]'s JSON laid over the config Syncthing [served]. Syncthing resets
 * every field a posted config lacks, and the Gson models only declare some of them, so the fields
 * the models drop come from [served]. List entries are matched by their `id` or `deviceID`; the
 * model decides which entries exist and in what order. Maps the app edits as a whole, like the
 * versioning `params`, are taken from the model.
 */
internal fun mergeConfigJson(served: JsonObject, model: JsonObject): JsonObject {
    val merged = model.deepCopy()
    for ((key, servedValue) in served.entrySet()) {
        val modelValue = model[key]
        merged.add(
            key,
            when {
                modelValue == null -> servedValue.deepCopy()
                key in WHOLE_MAPS -> modelValue.deepCopy()
                else -> mergeConfigElement(servedValue, modelValue)
            },
        )
    }
    return merged
}

private val WHOLE_MAPS = setOf("params")
private val LIST_KEYS = listOf("id", "deviceID")

private fun mergeConfigElement(served: JsonElement, model: JsonElement): JsonElement =
    when {
        served.isJsonObject && model.isJsonObject ->
            mergeConfigJson(served.asJsonObject, model.asJsonObject)
        served.isJsonArray && model.isJsonArray ->
            mergeConfigList(served.asJsonArray, model.asJsonArray)
        else -> model.deepCopy()
    }

private fun mergeConfigList(served: JsonArray, model: JsonArray): JsonArray {
    val merged = JsonArray()
    for (entry in model) {
        val match =
            listEntryKey(entry)?.let { key -> served.firstOrNull { listEntryKey(it) == key } }
        merged.add(if (match == null) entry.deepCopy() else mergeConfigElement(match, entry))
    }
    return merged
}

private fun listEntryKey(entry: JsonElement): Pair<String, String>? {
    if (!entry.isJsonObject) return null
    val obj = entry.asJsonObject
    val name = LIST_KEYS.firstOrNull { obj[it]?.isJsonPrimitive == true } ?: return null
    return name to obj[name].asString
}

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
