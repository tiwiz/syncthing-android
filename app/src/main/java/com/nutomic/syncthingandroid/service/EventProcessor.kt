package com.nutomic.syncthingandroid.service

import android.content.AsyncQueryHandler
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import androidx.core.content.edit
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.util.splitLikeJava
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Polls Syncthing's events through [RestApi] every few seconds and applies them: caches in RestApi,
 * the media store, and notifications. All callbacks run on the main thread.
 */
class EventProcessor(private val context: Context, private val restApi: RestApi) :
    Runnable, RestApi.OnReceiveEventListener {

    lateinit var preferences: SharedPreferences
    lateinit var notificationHandler: NotificationHandler

    private val enableVerboseLog: Boolean

    /**
     * Use the main thread for all callbacks and message handling, or we have to track down nasty
     * threading problems. Also the lock for [shutdown].
     */
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastEventId = 0L
    @Volatile private var shutdown = true

    /** What each event type does; types without a handler are logged. */
    private val handlers: Map<String, (Event, JsonElement) -> Unit> =
        mapOf(
            "ConfigSaved" to
                { _, _ ->
                    logV("Forwarding ConfigSaved event to RestApi to get the updated config.")
                    restApi.reloadConfig()
                },
            "DeviceConnected" to
                { e, _ ->
                    restApi.updateRemoteDeviceConnected(e.string("id"), true)
                },
            "DeviceDisconnected" to
                { e, _ ->
                    restApi.updateRemoteDeviceConnected(e.string("id"), false)
                },
            "DevicePaused" to
                { e, _ ->
                    restApi.updateRemoteDevicePaused(e.string("device"), true)
                },
            "DeviceResumed" to
                { e, _ ->
                    restApi.updateRemoteDevicePaused(e.string("device"), false)
                },
            "FolderCompletion" to { e, _ -> onFolderCompletion(restApi, e) },
            "FolderErrors" to
                { e, json ->
                    logV("Event ${e.type}, data ${e.data}")
                    onFolderErrors(json)
                },
            "FolderPaused" to { e, _ -> restApi.updateLocalFolderPause(e.string("id"), true) },
            "FolderResumed" to { e, _ -> restApi.updateLocalFolderPause(e.string("id"), false) },
            "FolderSummary" to { e, json -> onFolderSummary(restApi, json, e.string("folder")) },
            "ItemFinished" to { e, _ -> onItemFinishedEvent(e) },
            "LocalIndexUpdated" to
                { e, json ->
                    logV("Event ${e.type}, data ${e.data}")
                    onLocalIndexUpdated(restApi, json, e.string("folder"), e.time, ::logV)
                },
            "PendingDevicesChanged" to
                { e, _ ->
                    e.list<Map<String, String?>>("added")?.forEach(::onPendingDevicesChanged)
                },
            "PendingFoldersChanged" to
                { e, _ ->
                    e.list<Map<String, Any?>>("added")?.forEach(::onPendingFoldersChanged)
                },
            "StateChanged" to
                { e, _ ->
                    restApi.updateLocalFolderState(e.string("folder"), e.string("to"))
                },
            "RemoteIndexUpdated" to
                { e, _ ->
                    onRemoteIndexUpdated(
                        e.string("device"),
                        e.string("folder"),
                        e.data?.get("items") as Double? ?: 0.0,
                    )
                },
            "Starting" to { e, _ -> logV("Ignored event ${e.type}, data ${e.data}") },
            "StartupComplete" to { e, _ -> logV("Ignored event ${e.type}, data ${e.data}") },
        )

    init {
        preferences = context.appDependencies().preferences()
        notificationHandler = context.appDependencies().notificationHandler()
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
    }

    override fun run() {
        // Restore the last event id if the event processor may have been restarted.
        if (lastEventId == 0L) {
            lastEventId = preferences.getLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 0)
        }
        // First check if the event number ran backwards. If that's the case we've to start at zero
        // because syncthing was restarted.
        restApi.getEvents(
            0,
            1,
            object : RestApi.OnReceiveEventListener {
                // Reschedule, or polling stops for good.
                override fun onError() = this@EventProcessor.onError()

                override fun onEvent(event: Event, json: JsonElement) = Unit

                override fun onDone(lastId: Long) {
                    if (lastId < lastEventId) lastEventId = 0
                    logV("Reading events starting with id $lastEventId")
                    restApi.getEvents(lastEventId, 0, this@EventProcessor)
                }
            },
        )
    }

    /** Performs the actual event handling. */
    override fun onEvent(event: Event, json: JsonElement) {
        val type = event.type
        val handler = handlers[type]
        when {
            handler != null -> handler(event, json)
            type in IGNORED_EVENTS -> Unit
            else -> Log.d(TAG, "Unhandled event $type")
        }
    }

    override fun onDone(lastId: Long) {
        if (lastEventId < lastId) {
            lastEventId = lastId
            // Store the last EventId in case we get killed
            preferences.edit { putLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, lastEventId) }
        }
        reschedule()
    }

    override fun onError() {
        synchronized(mainThreadHandler) {
            if (!shutdown) {
                Log.d(TAG, "Event sink aborted, will retry in $EVENT_UPDATE_INTERVAL ms")
            }
        }
        reschedule()
    }

    private fun reschedule() {
        synchronized(mainThreadHandler) {
            if (!shutdown) {
                mainThreadHandler.removeCallbacks(this)
                mainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL)
            }
        }
    }

    fun start() {
        Log.d(TAG, "Starting event processor.")
        // Remove all pending callbacks and add a new one. This makes sure that only one event
        // poller
        // is running at any given time.
        synchronized(mainThreadHandler) {
            shutdown = false
            mainThreadHandler.removeCallbacks(this)
            mainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping event processor.")
        synchronized(mainThreadHandler) {
            shutdown = true
            mainThreadHandler.removeCallbacks(this)
        }
    }

    private fun onItemFinishedEvent(event: Event) {
        val action = event.string("action")
        val error = event.string("error")
        val folderId = event.string("folder")
        val relativeFilePath = event.string("item")
        // Look up folder.path for the given folder.id if all fields were contained in the event.
        var folderPath: String? = null
        var folderType: String? = null
        if (
            !TextUtils.isEmpty(action) &&
                !TextUtils.isEmpty(folderId) &&
                !TextUtils.isEmpty(relativeFilePath)
        ) {
            restApi.folders
                .firstOrNull { checkNotNull(it.id) == folderId }
                ?.let {
                    folderPath = it.path
                    folderType = it.type
                }
        }
        if (TextUtils.isEmpty(folderPath) && TextUtils.isEmpty(folderType)) {
            Log.w(
                TAG,
                "ItemFinished: Failed to determine folder.path for folder.id=\"${folderId.orEmpty()}\"",
            )
            return
        }
        if (TextUtils.isEmpty(error)) {
            // We don't intend to show errors as the last synced item on the UI.
            restApi.setLocalFolderLastItemFinished(folderId, action, relativeFilePath, event.time)
        }
        onItemFinished(
            checkNotNull(action),
            error,
            folderType,
            folderPath + File.separator + relativeFilePath,
        )
    }

    private fun onPendingDevicesChanged(added: Map<String, String?>) {
        val deviceId = added["deviceID"] ?: return
        val deviceName = added["name"]
        Log.d(TAG, "Unknown device '$deviceName' ($deviceId) wants to connect")
        // Show device approve/ignore notification.
        notificationHandler.showDeviceConnectNotification(
            deviceId,
            checkNotNull(deviceName),
            added["address"],
        )
    }

    private fun onPendingFoldersChanged(added: Map<String, Any?>) {
        val deviceId = checkNotNull(added["deviceID"]).toString()
        val folderId = checkNotNull(added["folderID"]).toString()
        val folderLabel = checkNotNull(added["folderLabel"]).toString()
        val receiveEncrypted = added["receiveEncrypted"] as Boolean
        val remoteEncrypted = added["remoteEncrypted"] as Boolean
        Log.d(TAG, "Device '$deviceId' wants to share folder '$folderLabel' ($folderId)")
        // Find the deviceName corresponding to the deviceId.
        val deviceName =
            restApi
                .getDevices(false)
                .firstOrNull { checkNotNull(it.deviceID) == deviceId }
                ?.displayName
        val isNewFolder = restApi.folders.none { it.id == folderId }
        // Show folder approve/ignore notification.
        notificationHandler.showFolderShareNotification(
            deviceId,
            deviceName,
            folderId,
            folderLabel,
            receiveEncrypted,
            remoteEncrypted,
            isNewFolder,
        )
    }

    private fun onFolderErrors(json: JsonElement) {
        val data = (json as JsonObject).get("data")
        if (data == null) {
            Log.e(TAG, "onFolderErrors: data == null")
            return
        }
        val errors = (data as JsonObject).get("errors") as JsonArray?
        if (errors == null) {
            Log.e(TAG, "onFolderErrors: errors == null")
            return
        }
        for (error in errors) {
            if (error == null) continue
            val strError = jsonString((error as JsonObject).get("error"))
            val strPath = jsonString(error.get("path"))
            if (
                !TextUtils.isEmpty(strError) &&
                    !TextUtils.isEmpty(strPath) &&
                    checkNotNull(strError).contains("insufficient space in basic")
            ) {
                notificationHandler.showCrashedNotification(
                    R.string.notification_out_of_disk_space,
                    lastTwoSegments(checkNotNull(strPath)),
                )
            }
        }
    }

    /** Tells the media store about a synced file. Precondition: action != null */
    private fun onItemFinished(
        action: String,
        error: String?,
        folderType: String?,
        fullFilePath: String,
    ) {
        if (!TextUtils.isEmpty(error)) {
            Log.e(TAG, "onItemFinished: Error \"$error\" reported on file: $fullFilePath")
            if (checkNotNull(error).contains("no space left on device")) {
                notificationHandler.showCrashedNotification(
                    R.string.notification_out_of_disk_space,
                    lastTwoSegments(fullFilePath),
                )
            }
            return
        }
        // Skip notifying Android's MediaStore, MediaScanner.
        if (folderType == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) return
        when (action) {
            // file deleted
            "delete" -> {
                if (File(fullFilePath).exists()) {
                    Log.i(
                        TAG,
                        "onItemFinished: MediaStore, Skip file deletion because file exists: $fullFilePath",
                    )
                    return
                }
                Log.i(TAG, "onItemFinished: MediaStore, Deleting file: $fullFilePath")
                LoggingAsyncQueryHandler(context.contentResolver)
                    .startDelete(
                        // Passed to onDeleteComplete as token and cookie.
                        0,
                        fullFilePath,
                        MediaStore.Files.getContentUri("external"),
                        MediaStore.Images.ImageColumns.DATA + " = ?",
                        arrayOf(fullFilePath),
                    )
            }
            // file contents changed
            "update" -> {
                Log.i(TAG, "onItemFinished: MediaScanner, Rescanning file: $fullFilePath")
                MediaScannerConnection.scanFile(context, arrayOf(fullFilePath), null, null)
            }
            // file metadata changed but not contents
            "metadata" -> Log.i(TAG, "onItemFinished: MediaScanner, Skipping file: $fullFilePath")
            else -> Log.w(TAG, "onItemFinished: Unhandled action \"$action\"")
        }
    }

    private fun onRemoteIndexUpdated(deviceId: String?, folderId: String?, items: Double) {
        if (deviceId == null || folderId == null) return
        if (items > 0) {
            restApi.setRemoteIndexUpdated(deviceId, folderId, true)
        }
    }

    /** Logs nothing; the original only called super. Kept for the MediaStore deletes. */
    private class LoggingAsyncQueryHandler(contentResolver: ContentResolver) :
        AsyncQueryHandler(contentResolver) {
        override fun onDeleteComplete(token: Int, cookie: Any?, result: Int) {
            // Like the Java original, this forwards to onUpdateComplete.
            super.onUpdateComplete(token, cookie, result)
        }
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    private companion object {
        const val TAG = "EventProcessor"

        /**
         * Minimum interval at which the events are polled from syncthing and processed. This
         * interval will not wake up the device to save battery power.
         */
        val EVENT_UPDATE_INTERVAL = TimeUnit.SECONDS.toMillis(5)

        /** Event types that change nothing in the app. */
        val IGNORED_EVENTS =
            setOf(
                "Ping",
                "DeviceDiscovered",
                "DownloadProgress",
                "FolderScanProgress",
                "FolderWatchStateChanged",
                "ItemStarted",
                "ListenAddressesChanged",
                "LoginAttempt",
                "RemoteDownloadProgress",
            )

        fun Event.string(key: String): String? = data?.get(key) as String?

        @Suppress("UNCHECKED_CAST") // Gson builds JSON arrays of objects as lists of maps.
        fun <T> Event.list(key: String): List<T>? = data?.get(key) as List<T>?

        /** The value of a JSON string without its quotes, or null if it is not a string. */
        fun jsonString(element: JsonElement?): String? =
            if (element == null || !element.isJsonPrimitive) null else element.asString

        /** "folder/file" from a path, or the path itself without a folder. */
        fun lastTwoSegments(path: String): String {
            val segments = path.splitLikeJava(File.separator)
            return if (segments.size < 2) path
            else segments[segments.size - 2] + File.separator + segments[segments.size - 1]
        }
    }
}

private const val TOP_TAG = "EventProcessor"
private val QUOTES = Regex("^\"|\"$")

private fun onFolderCompletion(restApi: RestApi, event: Event) {
    val data = checkNotNull(event.data)
    restApi.setRemoteCompletionInfo(
        data["device"] as String?,
        data["folder"] as String?,
        data["needBytes"] as Double?,
        data["completion"] as Double?,
    )
}

private fun onFolderSummary(restApi: RestApi, json: JsonElement, folderId: String?) {
    val data = (json as JsonObject).get("data")
    if (data == null) {
        Log.e(TOP_TAG, "onFolderSummary: data == null")
        return
    }
    val summary = (data as JsonObject).get("summary")
    if (summary == null) {
        Log.e(TOP_TAG, "onFolderSummary: summary == null")
        return
    }
    val folderStatus =
        try {
            GsonBuilder().create().fromJson(summary, FolderStatus::class.java)
        } catch (e: JsonParseException) {
            Log.e(TOP_TAG, "onFolderSummary: gson.fromJson failed", e)
            return
        }
    restApi.setLocalFolderStatus(folderId, folderStatus)
}

/** Sends the last (latest) local change of the event to the UI. */
private fun onLocalIndexUpdated(
    restApi: RestApi,
    json: JsonElement,
    folderId: String?,
    dateTimeStamp: String?,
    logV: (String) -> Unit,
) {
    val data = (json as JsonObject).get("data")
    if (data == null) {
        Log.e(TOP_TAG, "onLocalIndexUpdated: data == null")
        return
    }
    val filenames = (data as JsonObject).get("filenames") as JsonArray?
    if (filenames == null) {
        Log.e(TOP_TAG, "onLocalIndexUpdated: filenames == null")
        return
    }
    for (i in 0 until filenames.size()) {
        val raw = filenames.get(i).toString()
        if (TextUtils.isEmpty(raw)) continue
        val filename = raw.replace(QUOTES, "")
        logV("onLocalIndexUpdated: filename=[$filename], time=[$dateTimeStamp]")
        if (i == filenames.size() - 1) {
            restApi.setLocalFolderLastItemFinished(folderId, "update", filename, dateTimeStamp)
        }
    }
}
