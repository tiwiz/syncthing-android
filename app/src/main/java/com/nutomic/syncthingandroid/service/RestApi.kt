package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.http.GetRequest
import com.nutomic.syncthingandroid.http.PostRequest
import com.nutomic.syncthingandroid.model.CachedFolderStatus
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.model.Config
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Connections
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DeviceStat
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.model.DiskEvent
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.LocalCompletion
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.RemoteCompletion
import com.nutomic.syncthingandroid.model.RemoteCompletionInfo
import com.nutomic.syncthingandroid.model.RemoteIgnoredDevice
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.model.SystemVersion
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.getLocalZonedDateTime
import com.nutomic.syncthingandroid.util.getSyncConflictFiles
import com.nutomic.syncthingandroid.util.killProcess
import com.nutomic.syncthingandroid.util.runScriptSet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Type
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * The local Syncthing's REST API: reads the config and status once at startup, keeps the config and
 * completion caches, sends config changes, and answers the screens' queries. Results arrive on the
 * main thread through listeners.
 */
// The API the screens and the service use, one method per Syncthing operation; the parts that need
// no requests live in RestApiSupport.kt.
@Suppress("TooManyFunctions", "LargeClass")
class RestApi(
    private val context: Context,
    private val notificationHandler: NotificationHandler,
    url: URL,
    val apiKey: String,
    private val onApiAvailableListener: OnApiAvailableListener,
    private val onConfigChangedListener: OnConfigChangedListener,
) {
    fun interface OnConfigChangedListener {
        fun onConfigChanged()
    }

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    fun interface OnApiAvailableListener {
        fun onApiAvailable()
    }

    /** Listener for [getEvents]. */
    interface OnReceiveEventListener {
        fun onError()

        /** Called for each event. */
        fun onEvent(event: Event, json: JsonElement)

        /**
         * Called after all available events have been processed, with the id of the last one: the
         * starting point for the next round.
         */
        fun onDone(lastId: Long)
    }

    private val enableVerboseLog: Boolean = AppPrefs.getPrefVerboseLog(context)

    /** The API's address; follows the GUI address after each config change. */
    var url: URL = url
        private set

    /** The Syncthing version, once read at startup. */
    var version: String? = null
        private set

    /** Guarded by [configLock]. */
    private var config: Config? = null

    /** The config as Syncthing last served it, with every field; guarded by [configLock]. */
    private var servedConfig: JsonObject? = null
    private val configLock = Any()

    // Results cached from the system status.
    private var localDeviceId: String? = null
    private var urVersionMax: Int? = null

    /**
     * Which startup queries finished; guarded by [asyncQueryCompleteLock]. When the last one
     * finishes, SyncthingService gets onApiAvailable: other screens need the cached config and
     * system information (e.g. the local device ID) by then, and the main thread must not wait on
     * synchronous REST queries.
     */
    private var asyncQueryConfigComplete = false
    private var asyncQueryVersionComplete = false
    private var asyncQuerySystemStatusComplete = false
    private val asyncQueryCompleteLock = Any()

    /** The latest results of device and folder completion events. */
    private val localCompletion = LocalCompletion(enableVerboseLog)
    private val remoteCompletion = RemoteCompletion(enableVerboseLog)
    private val connectionStats = ConnectionStats()
    private var lastOnlineDeviceCount = 0
    private var lastTotalSyncCompletion = -1

    @Volatile private var hasShutdown = false

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val gson: Gson = GsonBuilder().create()
    private val executorService = Executors.newSingleThreadExecutor()

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun get(
        path: String,
        params: Map<String, String>? = null,
        onError: ApiRequestErrorListener = ApiRequestErrorListener {},
        onSuccess: (String) -> Unit,
    ) {
        GetRequest(context, url, path, apiKey, params, onSuccess) { onError.onError() }
    }

    private fun post(path: String, params: Map<String, String>? = null, body: String? = null) {
        PostRequest(context, url, path, apiKey, params, body, null)
    }

    private fun interface ApiRequestErrorListener {
        fun onError()
    }

    /**
     * Gets the local device ID, the Syncthing version and the config, then calls onApiAvailable.
     */
    fun readConfigFromRestApi() {
        logV("Querying config from REST ...")
        synchronized(asyncQueryCompleteLock) {
            asyncQueryVersionComplete = false
            asyncQueryConfigComplete = false
            asyncQuerySystemStatusComplete = false
        }
        queryVersionForStartup()
        queryConfigForStartup()
        querySystemStatusForStartup()
    }

    private fun queryVersionForStartup() {
        get(
            GetRequest.URI_VERSION,
            onError = { retryStartupQuery(GetRequest.URI_VERSION, ::queryVersionForStartup) },
        ) {
            version = JsonParser.parseString(it).asJsonObject.get("version").asString
            updateDebugFacilitiesCache()
            synchronized(asyncQueryCompleteLock) {
                asyncQueryVersionComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
    }

    private fun queryConfigForStartup() {
        get(
            GetRequest.URI_CONFIG,
            onError = { retryStartupQuery(GetRequest.URI_CONFIG, ::queryConfigForStartup) },
        ) {
            onReloadConfigComplete(it)
            synchronized(asyncQueryCompleteLock) {
                asyncQueryConfigComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
    }

    private fun querySystemStatusForStartup() {
        val retry = {
            retryStartupQuery(GetRequest.URI_SYSTEM_STATUS, ::querySystemStatusForStartup)
        }
        get(GetRequest.URI_SYSTEM_STATUS, onError = retry) { result ->
            val info =
                try {
                    gson.fromJson(result, SystemStatus::class.java)
                } catch (_: JsonParseException) {
                    Log.e(
                        TAG,
                        "querySystemStatusForStartup: Parsing REST API result failed. result=$result",
                    )
                    retry()
                    return@get
                }
            localDeviceId = info.myID
            urVersionMax = info.urVersionMax
            synchronized(asyncQueryCompleteLock) {
                asyncQuerySystemStatusComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
    }

    /**
     * A startup query that fails is repeated until it succeeds or we shut down. Without the retry,
     * onApiAvailable never fires and SyncthingService stays in State.STARTING, where it also defers
     * its own shutdown.
     */
    private fun retryStartupQuery(uri: String, query: () -> Unit) {
        if (hasShutdown) {
            return
        }
        Log.w(TAG, "readConfigFromRestApi: $uri failed, retrying in $STARTUP_RETRY_DELAY_MS ms")
        mainThreadHandler.postDelayed({ if (!hasShutdown) query() }, STARTUP_RETRY_DELAY_MS)
    }

    private fun checkReadConfigFromRestApiCompleted() {
        if (
            asyncQueryVersionComplete && asyncQueryConfigComplete && asyncQuerySystemStatusComplete
        ) {
            logV("Reading config from REST completed. Syncthing version is $version")
            // Tell SyncthingService it can transition to State.ACTIVE.
            onApiAvailableListener.onApiAvailable()
            triggerVersioningCleanupIfNecessary()
        }
    }

    /**
     * Every tenth start, lowers the versioning cleanup interval for a while to force a cleanup.
     * Syncthing has no request for that. Each folder's own interval is saved first and put back
     * afterwards, or on the next start when Syncthing stopped too soon.
     */
    private fun triggerVersioningCleanupIfNecessary() {
        restoreVersioningCleanupIntervals()
        var startupCounter = preferences.getInt(Constants.PREF_APP_START_COUNTER, 0) + 1
        if (startupCounter == Int.MAX_VALUE) startupCounter = 1
        preferences.edit { putInt(Constants.PREF_APP_START_COUNTER, startupCounter) }
        if (startupCounter % VERSIONING_CLEANUP_EVERY_STARTS != 0) {
            logV(
                "Skipping versioning cleanup because it is only triggered every 10th startup to save resources."
            )
            return
        }
        if (!forceVersioningCleanup()) return
        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    if (hasShutdown) {
                        logV("Restoring the versioning cleanup intervals on the next start.")
                    } else {
                        restoreVersioningCleanupIntervals()
                    }
                },
                FORCED_CLEANUP_MILLIS,
            )
    }

    /**
     * Saves the intervals of the folders that clean up their versions, then lowers them. False when
     * there is no such folder.
     */
    private fun forceVersioningCleanup(): Boolean {
        synchronized(configLock) {
            val folders =
                checkNotNull(checkNotNull(config).folders).filter {
                    val versioning = checkNotNull(it.versioning)
                    !versioning.type.isNullOrEmpty() && versioning.cleanupIntervalS > 0
                }
            if (folders.isEmpty()) return false
            val saved = folders.associate {
                checkNotNull(it.id) to checkNotNull(it.versioning).cleanupIntervalS
            }
            preferences.edit(commit = true) {
                putString(Constants.PREF_VERSIONING_CLEANUP_INTERVALS, gson.toJson(saved))
            }
            for (folder in folders) {
                checkNotNull(folder.versioning).cleanupIntervalS = FORCED_CLEANUP_INTERVAL_S
            }
            logV("Set VersioningCleanupIntervalS to $FORCED_CLEANUP_INTERVAL_S")
            sendConfig()
            return true
        }
    }

    /**
     * Puts back the intervals [forceVersioningCleanup] saved, where the forced one is still set.
     */
    private fun restoreVersioningCleanupIntervals() {
        val json =
            preferences.getString(Constants.PREF_VERSIONING_CLEANUP_INTERVALS, null) ?: return
        val saved: Map<String, Int> =
            try {
                gson.fromJson(json, CLEANUP_INTERVALS_TYPE)
            } catch (e: JsonParseException) {
                Log.w(TAG, "restoreVersioningCleanupIntervals: Unreadable saved intervals", e)
                emptyMap()
            }
        synchronized(configLock) {
            var changed = false
            for (folder in checkNotNull(checkNotNull(config).folders)) {
                val interval = saved[folder.id] ?: continue
                val versioning = checkNotNull(folder.versioning)
                if (versioning.cleanupIntervalS == FORCED_CLEANUP_INTERVAL_S) {
                    versioning.cleanupIntervalS = interval
                    changed = true
                }
            }
            preferences.edit(commit = true) { remove(Constants.PREF_VERSIONING_CLEANUP_INTERVALS) }
            logV("Restored the versioning cleanup intervals: $saved")
            if (changed) sendConfig()
        }
    }

    fun reloadConfig() {
        get(GetRequest.URI_CONFIG) { onReloadConfigComplete(it) }
    }

    private fun onReloadConfigComplete(configResult: String) {
        val parsed = gson.fromJson(configResult, Config::class.java)
        synchronized(configLock) {
            config = parsed
            servedConfig = JsonParser.parseString(configResult).asJsonObject
        }
        checkNotNull(parsed) { "config is null: $configResult" }
        Log.d(TAG, "onReloadConfigComplete: Successfully parsed configuration.")
        synchronized(configLock) {
            val logRemoteIgnoredDevices = gson.toJson(parsed.remoteIgnoredDevices)
            if (logRemoteIgnoredDevices != "[]") {
                logV("ORCC: remoteIgnoredDevices = $logRemoteIgnoredDevices")
            }
            for (device in getDevices(false)) {
                val logIgnoredFolders = gson.toJson(device.ignoredFolders)
                if (logIgnoredFolders != "[]") {
                    logV("ORCC: device[${device.displayName}].ignoredFolders = $logIgnoredFolders")
                }
            }
        }
        get(GetRequest.URI_PENDING_DEVICES) { notifyPendingDevices(it, gson, notificationHandler) }
        get(GetRequest.URI_PENDING_FOLDERS) {
            notifyPendingFolders(it, gson, notificationHandler, getDevices(false), folders)
        }
        // Update cached device and folder information.
        val tmpFolders = folders
        localCompletion.updateFromConfig(tmpFolders)
        remoteCompletion.updateFromConfig(getDevices(true), tmpFolders)
        // Perform first query for remote device status by forcing a cache miss.
        getRemoteDeviceStatus("")
        for (folder in tmpFolders) {
            for (device in checkNotNull(folder.sharedWithDevices)) {
                queryCompletion(folder.id, device.deviceID, device.displayName)
            }
        }
    }

    private fun queryCompletion(folderId: String?, deviceId: String?, deviceName: String) {
        get(
            GetRequest.URI_DB_COMPLETION,
            mapOf("device" to checkNotNull(deviceId), "folder" to checkNotNull(folderId)),
        ) { result ->
            val completionInfo = gson.fromJson(result, CompletionInfo::class.java)
            logV(
                "ORCC: /rest/db/completion: folder=$folderId, device=$deviceName" +
                    ", completion=${completionInfo.completion}" +
                    ", needBytes=${String.format(Locale.getDefault(), "%.0f", completionInfo.needBytes)}" +
                    ", remoteState=${completionInfo.remoteState}"
            )
            val remoteCompletionInfo =
                RemoteCompletionInfo().apply {
                    completion = completionInfo.completion
                    needBytes = completionInfo.needBytes
                }
            remoteCompletion.setCompletionInfo(deviceId, folderId, remoteCompletionInfo)
        }
    }

    /**
     * Queries the debug facilities of the running binary when its version changed; the first launch
     * counts as a change. Precondition: [version] read from REST.
     */
    private fun updateDebugFacilitiesCache() {
        val currentVersion = checkNotNull(version)
        if (currentVersion == preferences.getString(Constants.PREF_LAST_BINARY_VERSION, "")) {
            return
        }
        get(GetRequest.URI_SYSTEM_LOGLEVELS) { result ->
            try {
                val facilities =
                    JsonParser.parseString(result).asJsonObject.getAsJsonObject("packages").keySet()
                preferences.edit {
                    putStringSet(Constants.PREF_DEBUG_FACILITIES_AVAILABLE, HashSet(facilities))
                }
                // Store the binary version, so this runs again only after a binary update.
                preferences.edit { putString(Constants.PREF_LAST_BINARY_VERSION, currentVersion) }
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Any unexpected answer leaves the cache as it was.
                Log.w(
                    TAG,
                    "updateDebugFacilitiesCache: Failed to get debug facilities. result=$result",
                    e,
                )
            }
        }
    }

    /**
     * Permanently ignores a device when it tries to connect: ignored devices do not trigger the
     * "DeviceRejected" event.
     */
    fun ignoreDevice(deviceId: String, deviceName: String?, deviceAddress: String?) {
        synchronized(configLock) {
            val current = checkNotNull(config)
            val ignored = checkNotNull(current.remoteIgnoredDevices)
            if (ignored.any { deviceId == it.deviceID }) {
                Log.d(TAG, "Device already ignored [$deviceId]")
                return
            }
            // Moves the "pendingDevice" entry to a new "remoteIgnoredDevice" entry.
            current.remoteIgnoredDevices =
                ignored +
                    RemoteIgnoredDevice().apply {
                        deviceID = deviceId
                        address = deviceAddress.orEmpty()
                        name = deviceName.orEmpty()
                        time = getLocalZonedDateTime()
                    }
            sendConfig()
            Log.d(TAG, "Ignored device [$deviceId]")
        }
    }

    /**
     * Permanently ignores a folder share request: ignored folders do not trigger the
     * "FolderRejected" event.
     */
    fun ignoreFolder(deviceId: String, folderId: String, folderLabel: String?) {
        synchronized(configLock) {
            val device =
                checkNotNull(checkNotNull(config).devices).firstOrNull { deviceId == it.deviceID }
                    ?: return
            val ignored = checkNotNull(device.ignoredFolders)
            if (ignored.any { folderId == it.id }) {
                Log.d(TAG, "ignoreFolder: Folder [$folderId] already ignored on device [$deviceId]")
                return
            }
            // Moves the "pendingFolder" entry to a new "ignoredFolder" entry.
            device.ignoredFolders =
                ignored +
                    IgnoredFolder().apply {
                        id = folderId
                        label = folderLabel.orEmpty()
                        time = getLocalZonedDateTime()
                    }
            logV("ignoreFolder: device.ignoredFolders = " + gson.toJson(device.ignoredFolders))
            sendConfig()
            Log.d(TAG, "Ignored folder [$folderId] announced by device [$deviceId]")
        }
    }

    /** Undoes ignoring devices and folders. */
    fun undoIgnoredDevicesAndFolders() {
        Log.d(TAG, "Undo ignoring devices and folders ...")
        synchronized(configLock) {
            val current = checkNotNull(config)
            current.remoteIgnoredDevices = ArrayList()
            checkNotNull(current.devices).forEach { it.ignoredFolders = ArrayList() }
        }
    }

    /** Overrides folder changes, like the web UI's "override changes" button. */
    fun overrideChanges(folderId: String) {
        Log.d(TAG, "overrideChanges '$folderId'")
        post(PostRequest.URI_DB_OVERRIDE, mapOf("folder" to folderId))
    }

    /** Rescans all folders. */
    fun rescanAll() {
        Log.d(TAG, "rescanAll")
        post(PostRequest.URI_DB_SCAN)
    }

    /** Reverts local folder changes, like the web UI's "Revert local changes" button. */
    fun revertLocalChanges(folderId: String) {
        Log.d(TAG, "revertLocalChanges '$folderId'")
        post(PostRequest.URI_DB_REVERT, mapOf("folder" to folderId))
    }

    val webGuiUrl: URL
        get() =
            synchronized(configLock) {
                try {
                    val address = checkNotNull(checkNotNull(config).gui).address
                    if (address == null) {
                        Log.e(
                            TAG,
                            "getWebGuiUrl: mConfig.gui.address == null, returning 127.0.0.1:8384",
                        )
                        return URL("https://127.0.0.1:8384")
                    }
                    URL("https://$address")
                } catch (e: MalformedURLException) {
                    throw IllegalStateException("Failed to parse web interface URL", e)
                }
            }

    /**
     * Sends the current config to Syncthing. This results in a "ConfigSaved" event, which makes
     * EventProcessor call [reloadConfig].
     */
    fun sendConfig() {
        val jsonConfig =
            synchronized(configLock) {
                val model = gson.toJsonTree(config).asJsonObject
                val merged = servedConfig?.let { mergeConfigJson(it, model) } ?: model
                // Later posts before the next reload build on this one.
                servedConfig = merged
                gson.toJson(merged)
            }
        post(PostRequest.URI_SYSTEM_CONFIG, body = jsonConfig)
        url = webGuiUrl
        onConfigChangedListener.onConfigChanged()
    }

    /** Asks Syncthing to shut down: SyncthingNative exits and does not restart. */
    fun shutdown() {
        hasShutdown = true
        executorService.shutdownNow()
        killProcess("find")
        post(PostRequest.URI_SYSTEM_SHUTDOWN)
    }

    /** The folders, sorted by label (or ID without one), with "~/" paths expanded; copies. */
    val folders: List<Folder>
        get() {
            val copy =
                synchronized(configLock) {
                    deepCopyWithGson(checkNotNull(checkNotNull(config).folders), FOLDER_LIST_TYPE)
                }
            for (folder in copy) {
                val path = checkNotNull(folder.path)
                if (path.startsWith("~/")) {
                    folder.path =
                        path.replaceFirst(Regex("^~"), FileUtils.getSyncthingTildeAbsolutePath())
                }
            }
            return copy.sortedWith(FOLDERS_COMPARATOR)
        }

    fun getFolderByID(folderID: String?): Folder? = folders.firstOrNull {
        checkNotNull(it.id) == folderID
    }

    /** Adds a new folder; only used for new folder creation. */
    fun addFolder(folder: Folder) {
        synchronized(configLock) {
            val current = checkNotNull(config)
            current.folders = checkNotNull(current.folders) + folder
            // Send model changes to syncthing, does not require a restart.
            sendConfig()
        }
    }

    fun updateFolder(newFolder: Folder) {
        synchronized(configLock) {
            removeFolderInternal(newFolder.id)
            val current = checkNotNull(config)
            current.folders = checkNotNull(current.folders) + newFolder
            sendConfig()
        }
    }

    fun removeFolder(id: String) {
        synchronized(configLock) {
            removeFolderInternal(id)
            // The completion caches are updated after the ConfigSaved event.
            sendConfig()
        }
        // Remove saved data from share activity for this folder.
        preferences.edit { remove(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + id) }
    }

    private fun removeFolderInternal(id: String?) {
        synchronized(configLock) {
            val current = checkNotNull(config)
            current.folders =
                checkNotNull(current.folders).withoutFirst { checkNotNull(it.id) == id }
        }
    }

    /** All devices, the local one only if [includeLocal]; copies. */
    fun getDevices(includeLocal: Boolean): List<Device> {
        val devices =
            synchronized(configLock) {
                deepCopyWithGson(checkNotNull(checkNotNull(config).devices), DEVICE_LIST_TYPE)
            }
        return if (includeLocal) devices else devices.withoutFirst { localDeviceId == it.deviceID }
    }

    val localDevice: Device
        get() {
            val devices = getDevices(true)
            check(devices.isNotEmpty()) { "RestApi.getLocalDevice: devices is empty." }
            logV("getLocalDevice: Looking for local device ID $localDeviceId")
            val local =
                devices.firstOrNull { checkNotNull(it.deviceID) == localDeviceId }
                    ?: error(
                        "RestApi.getLocalDevice: Failed to get the local device crucial to continuing execution."
                    )
            return deepCopyWithGson(local, Device::class.java)
        }

    /** Adds or updates a device identified by its device ID. */
    fun updateDevice(newDevice: Device) {
        synchronized(configLock) {
            removeDeviceInternal(newDevice.deviceID)
            val current = checkNotNull(config)
            current.devices = checkNotNull(current.devices) + newDevice
            sendConfig()
        }
    }

    fun removeDevice(deviceId: String) {
        synchronized(configLock) {
            removeDeviceInternal(deviceId)
            // The remote completion cache is updated after the ConfigSaved event.
            sendConfig()
        }
    }

    private fun removeDeviceInternal(deviceId: String?) {
        synchronized(configLock) {
            val current = checkNotNull(config)
            current.devices =
                checkNotNull(current.devices).withoutFirst { checkNotNull(it.deviceID) == deviceId }
        }
    }

    val options: Options
        get() =
            synchronized(configLock) {
                deepCopyWithGson(checkNotNull(checkNotNull(config).options), Options::class.java)
            }

    val gui: Gui
        get() =
            synchronized(configLock) {
                deepCopyWithGson(checkNotNull(checkNotNull(config).gui), Gui::class.java)
            }

    fun editSettings(newGui: Gui, newOptions: Options) {
        synchronized(configLock) {
            val current = checkNotNull(config)
            current.gui = newGui
            current.options = newOptions
        }
    }

    fun updateGui(newGui: Gui) {
        synchronized(configLock) {
            checkNotNull(config).gui = newGui
            sendConfig()
        }
    }

    /** Requests and parses the current system status and resource usage. */
    fun getSystemStatus(listener: OnResultListener1<SystemStatus>) {
        get(GetRequest.URI_SYSTEM_STATUS) { result ->
            try {
                listener.onResult(gson.fromJson(result, SystemStatus::class.java))
            } catch (_: JsonParseException) {
                Log.e(TAG, "getSystemStatus: Parsing REST API result failed. result=$result")
            }
        }
    }

    val isConfigLoaded: Boolean
        get() = synchronized(configLock) { config != null }

    /** Requests locally discovered devices. */
    fun getDiscoveredDevices(listener: OnResultListener1<Map<String, DiscoveredDevice>>) {
        get(GetRequest.URI_SYSTEM_DISCOVERY) {
            listener.onResult(gson.fromJson(it, DISCOVERED_TYPE))
        }
    }

    /** Requests the ignore list of the folder. */
    fun getFolderIgnoreList(folderId: String?, listener: OnResultListener1<FolderIgnoreList>) {
        get(GetRequest.URI_DB_IGNORES, mapOf("folder" to checkNotNull(folderId))) {
            listener.onResult(gson.fromJson(it, FolderIgnoreList::class.java))
        }
    }

    /** Posts the ignore list of the folder. */
    fun postFolderIgnoreList(folderId: String?, ignore: Array<String>) {
        val folderIgnoreList = FolderIgnoreList().apply { this.ignore = ignore }
        post(
            PostRequest.URI_DB_IGNORES,
            mapOf("folder" to checkNotNull(folderId)),
            gson.toJson(folderIgnoreList),
        )
    }

    /** Requests and parses system version information. */
    fun getSystemVersion(listener: OnResultListener1<SystemVersion>) {
        get(GetRequest.URI_VERSION) {
            listener.onResult(gson.fromJson(it, SystemVersion::class.java))
        }
    }

    /**
     * Status information about the device from the cache; "" queries the status of all devices for
     * an initially empty cache.
     */
    fun getRemoteDeviceStatus(deviceId: String?): Connection {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        if (cacheEntry.at.isNotEmpty()) {
            return cacheEntry
        }
        // Cache miss: query, so the information is available on a future call.
        if (!TextUtils.isEmpty(deviceId)) {
            logV("getRemoteDeviceStatus: Cache miss, deviceId=\"$deviceId\". Performing query.")
        }
        // Answers come for all devices, and storing all of them does not hurt.
        get(GetRequest.URI_CONNECTIONS) { result ->
            val connections = gson.fromJson(result, Connections::class.java)
            connectionStats.calculate(connections)
            for ((id, connection) in checkNotNull(connections.connections)) {
                remoteCompletion.setDeviceStatus(id, connection)
            }
        }
        // The last seen times, including the local device's.
        get(GetRequest.URI_STATS_DEVICE) { result ->
            for ((resultDeviceId, value) in
                JsonParser.parseString(result).asJsonObject.entrySet()) {
                val deviceStat = gson.fromJson(value, DeviceStat::class.java)
                preferences.edit {
                    putString(
                        Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + resultDeviceId,
                        deviceStat.lastSeen,
                    )
                }
            }
        }
        return cacheEntry
    }

    fun getRemoteDeviceCompletion(deviceId: String?): Int =
        remoteCompletion.getDeviceCompletion(deviceId)

    fun getRemoteDeviceNeedBytes(deviceId: String?): Double =
        remoteCompletion.getDeviceNeedBytes(deviceId)

    val totalConnectionStatistic: Connection
        get() = connectionStats.total

    /**
     * Overall sync completion of the running folder and device transfers, from the caches: folders
     * pull changes from remotes, devices pull changes from us. -1 without connected devices.
     */
    val totalSyncCompletion: Int
        get() {
            val totalDeviceCompletion = remoteCompletion.totalDeviceCompletion
            if (totalDeviceCompletion == -1) {
                return -1
            }
            val totalFolderCompletion = localCompletion.totalFolderCompletion
            val total =
                if (totalFolderCompletion == COMPLETE) totalDeviceCompletion
                else floor((totalFolderCompletion + totalDeviceCompletion).toDouble() / 2).toInt()
            return total.coerceIn(0, COMPLETE)
        }

    /**
     * Fetches the most recent disk events, newest first.
     *
     * "timeout=1" is important. /rest/events/disk is a long-poll endpoint: when no disk events are
     * buffered it blocks for the server default of 60s before returning an empty array, which made
     * the Recent changes screen appear to hang on open. The web GUI gets away without this because
     * it only ever calls the endpoint in the background and renders cached results, never blocking
     * the user. We fetch on screen open, so we want a query rather than a subscription.
     *
     * As a bonus this keeps each request well inside Volley's 5s socket timeout, so it is no longer
     * retried 5 times per call (see the "should not be retried" note in the server's getEvents).
     */
    fun getDiskEvents(limit: Int, listener: OnResultListener1<List<DiskEvent>>) {
        get(GetRequest.URI_EVENTS_DISK, mapOf("limit" to limit.toString(), "timeout" to "1")) {
            result ->
            try {
                val events = JsonParser.parseString(result).asJsonArray
                listener.onResult(
                    events.reversed().map { gson.fromJson(it, DiskEvent::class.java) }
                )
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Any answer that is not a list of disk events.
                Log.e(TAG, "getDiskEvents: Parsing REST API result failed. result=$result", e)
            }
        }
    }

    /** Retrieves the events since [sinceId]: one onEvent each, then onDone with the highest id. */
    fun getEvents(sinceId: Long, limit: Long, listener: OnReceiveEventListener) {
        get(
            GetRequest.URI_EVENTS,
            mapOf("since" to sinceId.toString(), "limit" to limit.toString()),
            onError = { listener.onError() },
        ) { result ->
            var lastId = 0L
            for (json in JsonParser.parseString(result).asJsonArray) {
                try {
                    val event = gson.fromJson(json, Event::class.java)
                    if (lastId < event.id) lastId = event.id.toLong()
                    listener.onEvent(event, json)
                } catch (_: JsonSyntaxException) {
                    Log.e(TAG, "getEvents: Skipping event due to JsonSyntaxException, raw=[$json]")
                }
            }
            listener.onDone(lastId)
        }
    }

    /** Status information about the folder from the cache; queried on a cache miss. */
    fun getFolderStatus(folderId: String?): Map.Entry<FolderStatus, CachedFolderStatus> {
        val cacheEntry = localCompletion.getFolderStatus(folderId)
        if (cacheEntry.key.stateChanged.isEmpty()) {
            // Cache miss because we haven't received a "FolderSummary" event yet.
            logV("getFolderStatus: Cache miss, folderId=\"$folderId\". Performing query.")
            get(GetRequest.URI_DB_STATUS, mapOf("folder" to checkNotNull(folderId))) { result ->
                val folder = getFolderByID(folderId)
                if (folder == null) {
                    Log.e(TAG, "getFolderStatus#GetRequest#onResult: folderId == null")
                    return@get
                }
                localCompletion.setFolderStatus(
                    folderId,
                    folder.paused,
                    gson.fromJson(result, FolderStatus::class.java),
                )
            }
        }
        return cacheEntry
    }

    private fun sendBroadcastToApps(intent: Intent) {
        for (packageId in BROADCAST_PACKAGES) {
            intent.setPackage(packageId)
            (context.applicationContext as SyncthingApp).sendBroadcast(
                intent,
                context.packageName + PERMISSION_RECEIVE_SYNC_STATUS,
            )
        }
    }

    fun sendBroadcastFolderSyncComplete(deviceId: String?, folder: Folder, folderState: String?) {
        val intent =
            Intent()
                .setAction(context.packageName + ACTION_NOTIFY_FOLDER_SYNC_COMPLETE)
                .putExtra("deviceId", deviceId)
                .putExtra("folderId", folder.id)
                .putExtra("folderLabel", folder.label)
                .putExtra("folderPath", folder.path)
                .putExtra("folderState", folderState)
        sendBroadcastToApps(intent)
    }

    /** Updates the cached folder status from event data. */
    fun setLocalFolderStatus(folderId: String?, folderStatus: FolderStatus) {
        localCompletion.setFolderStatus(folderId, folderStatus)
        onTotalSyncCompletionChange()
    }

    /** Records the last synced item; an "update" of a file that no longer exists was a delete. */
    fun setLocalFolderLastItemFinished(
        folderId: String?,
        lastItemFinishedAction: String?,
        lastItemFinishedItem: String?,
        lastItemFinishedTime: String?,
    ) {
        var realLastItemFinishedAction = checkNotNull(lastItemFinishedAction)
        if (realLastItemFinishedAction == "update") {
            val path = getFolderByID(folderId)?.path
            if (path != null && !File("$path/$lastItemFinishedItem").exists()) {
                realLastItemFinishedAction = "delete"
            }
        }
        localCompletion.setLastItemFinished(
            folderId,
            realLastItemFinishedAction,
            lastItemFinishedItem,
            lastItemFinishedTime,
        )
    }

    /**
     * Records a remote device's completion of a folder. When the folder completed, looks for sync
     * conflicts and, if remote changes arrived, runs the folder's completion work, both on a
     * background thread.
     */
    fun setRemoteCompletionInfo(
        deviceId: String?,
        folderId: String?,
        needBytes: Double?,
        completion: Double?,
    ) {
        val folder = getFolderByID(folderId)
        if (folder == null) {
            Log.e(TAG, "setRemoteCompletionInfo: folderId == null")
            return
        }
        val remoteCompletionInfo = RemoteCompletionInfo()
        if (folder.paused) {
            // EventProcessor reports paused folders as 0% complete, so devices sharing a paused and
            // another folder showed 50% here but 100% in the web UI: count paused ones as complete.
            logV(
                "setRemoteCompletionInfo: Paused folder \"$folderId\" - got " +
                    "${remoteCompletionInfo.completion}%, passing on 100%"
            )
            remoteCompletionInfo.completion = COMPLETE.toDouble()
            remoteCompletionInfo.needBytes = 0.0
        } else {
            remoteCompletionInfo.completion = checkNotNull(completion)
            remoteCompletionInfo.needBytes = checkNotNull(needBytes)
        }
        remoteCompletion.setCompletionInfo(deviceId, folderId, remoteCompletionInfo)
        onTotalSyncCompletionChange()
        planFolderCompletionWork(folder, deviceId, remoteCompletionInfo.completion)
    }

    private fun planFolderCompletionWork(folder: Folder, deviceId: String?, completion: Double) {
        val folderId = folder.id
        val cacheEntry = localCompletion.getFolderStatus(folderId)
        val folderStatus = cacheEntry.key
        var planGetSyncConflictFiles = false
        var planOnFolderSyncCompleted = false
        if (completion == COMPLETE.toDouble() && !folderStatus.state.contains("sync")) {
            planGetSyncConflictFiles = true
            if (cacheEntry.value.remoteIndexUpdated) {
                localCompletion.setRemoteIndexUpdated(folderId, false)
                planOnFolderSyncCompleted = true
            }
        }
        // On the way to shut down SyncthingNative, or nothing to do.
        if (hasShutdown || executorService.isShutdown) return
        if (!planGetSyncConflictFiles && !planOnFolderSyncCompleted) return
        executorService.execute {
            if (hasShutdown) return@execute
            if (planGetSyncConflictFiles) {
                // Check for ".sync-conflict-YYYYMMDD-HHMMSS-DEVICEI*" files.
                localCompletion.setDiscoveredConflictFiles(
                    folderId,
                    getSyncConflictFiles(checkNotNull(folder.path)),
                )
            }
            if (planOnFolderSyncCompleted) {
                onFolderSyncCompleted(folder, folderStatus.state, deviceId)
            }
        }
    }

    /** Runs the folder's script set if enabled, then tells subscribed apps. */
    fun onFolderSyncCompleted(folder: Folder, folderState: String?, deviceId: String?) {
        Log.d(TAG, "onFolderSyncCompleted: Completed folder=[${folder.id}]")
        if (
            preferences.getBoolean(
                Constants.dynPrefObjectFolderRunScript(checkNotNull(folder.id)),
                false,
            )
        ) {
            runScriptSet(folder.path + "/" + Constants.FILENAME_STFOLDER, arrayOf("sync_complete"))
        }
        sendBroadcastFolderSyncComplete(deviceId, folder, folderState)
    }

    @Suppress("UnusedParameter") // The device does not matter for the folder cache.
    fun setRemoteIndexUpdated(deviceId: String?, folderId: String?, remoteIndexUpdated: Boolean) {
        localCompletion.setRemoteIndexUpdated(folderId, remoteIndexUpdated)
    }

    /** Clears the folder's status cache when pausing or resuming it. */
    fun updateLocalFolderPause(folderId: String?, newPaused: Boolean) {
        localCompletion.setFolderStatus(folderId, newPaused, FolderStatus())
    }

    fun updateLocalFolderState(folderId: String?, newState: String?) {
        val cacheEntry = localCompletion.getFolderStatus(folderId)
        cacheEntry.key.state = checkNotNull(newState)
        localCompletion.setFolderStatus(folderId, cacheEntry.key)
    }

    fun updateRemoteDeviceConnected(deviceId: String?, newConnected: Boolean) {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        cacheEntry.connected = newConnected
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    fun updateRemoteDevicePaused(deviceId: String?, newPaused: Boolean) {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        cacheEntry.connected = false
        cacheEntry.paused = newPaused
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    /** The usage report, pretty printed. */
    fun getUsageReport(listener: OnResultListener1<String>) {
        get(GetRequest.URI_REPORT) {
            listener.onResult(
                GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(it))
            )
        }
    }

    val isUsageReportingAccepted: Boolean
        get() = options.isUsageReportingAccepted(checkNotNull(urVersionMax))

    val isUsageReportingDecided: Boolean
        get() = options.isUsageReportingDecided(checkNotNull(urVersionMax))

    fun setUsageReporting(acceptUsageReporting: Boolean) {
        val newOptions = options
        newOptions.urAccepted =
            if (acceptUsageReporting) checkNotNull(urVersionMax) else Options.USAGE_REPORTING_DENIED
        synchronized(configLock) { checkNotNull(config).options = newOptions }
    }

    /**
     * Saves the support bundle to [targetFile]; the listener learns whether that worked, also when
     * Syncthing could not produce it.
     */
    fun downloadSupportBundle(targetFile: File, listener: OnResultListener1<Boolean>?) {
        get(
            GetRequest.URI_DEBUG_SUPPORT,
            onError = {
                Log.w(TAG, "downloadSupportBundle: Syncthing did not send the support bundle")
                listener?.onResult(false)
            },
        ) { result ->
            logV("downloadSupportBundle: Writing '${targetFile.path}' ...")
            val ok =
                try {
                    if (!targetFile.exists()) targetFile.createNewFile()
                    // ISO-8859-1, not UTF-8: Volley read the ZIP's bytes as ISO-8859-1 characters.
                    FileOutputStream(targetFile).use {
                        it.write(result.toByteArray(Charsets.ISO_8859_1))
                    }
                    true
                } catch (e: IOException) {
                    Log.w(TAG, "downloadSupportBundle: Failed to write '${targetFile.path}' #1", e)
                    false
                }
            listener?.onResult(ok)
        }
    }

    /** Applies the folders' and devices' own run conditions; RunConditionMonitor asks for it. */
    fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(configLock) {
            val current = config
            if (current == null) {
                Log.w(TAG, "applyCustomRunConditions: mConfig is not ready yet.")
                return
            }
            val configChanged =
                applyObjectRunConditions(current, preferences, runConditionMonitor, ::logV)
                    ?: return
            if (configChanged) {
                logV("applyCustomRunConditions: Sending changed config ...")
                sendConfig()
            } else {
                logV("applyCustomRunConditions: No action was necessary.")
            }
        }
    }

    /** Updates the persistent notification when the device count or overall completion changed. */
    private fun onTotalSyncCompletionChange() {
        val onlineDeviceCount = remoteCompletion.onlineDeviceCount
        val totalSyncCompletion = totalSyncCompletion
        if (
            onlineDeviceCount == lastOnlineDeviceCount &&
                totalSyncCompletion == lastTotalSyncCompletion
        ) {
            return
        }
        // Do not persist the previous notification text.
        notificationHandler.updatePersistentNotification(
            context as SyncthingService,
            false,
            onlineDeviceCount,
            totalSyncCompletion,
        )
        lastOnlineDeviceCount = onlineDeviceCount
        lastTotalSyncCompletion = totalSyncCompletion
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    private companion object {
        const val TAG = "RestApi"

        /**
         * Intents we send to other apps that subscribed to us, appended to our application id (see
         * wiki/developers/Integrate-your-app-with-Syncthing-receive-folder-sync-status.md).
         */
        const val ACTION_NOTIFY_FOLDER_SYNC_COMPLETE = ".ACTION_NOTIFY_FOLDER_SYNC_COMPLETE"

        /**
         * Permission for apps receiving our broadcast intents, appended to our application id like
         * the permission declared in AndroidManifest.xml.
         */
        const val PERMISSION_RECEIVE_SYNC_STATUS = ".permission.RECEIVE_SYNC_STATUS"

        /** The apps that get the folder sync complete broadcast. */
        val BROADCAST_PACKAGES = listOf("org.decsync.cc")

        const val STARTUP_RETRY_DELAY_MS = 2000L
        const val VERSIONING_CLEANUP_EVERY_STARTS = 10
        const val FORCED_CLEANUP_INTERVAL_S = 2
        const val FORCED_CLEANUP_MILLIS = 10_000L
        const val COMPLETE = 100

        val FOLDER_LIST_TYPE: Type = object : TypeToken<List<Folder>>() {}.type
        val DEVICE_LIST_TYPE: Type = object : TypeToken<List<Device>>() {}.type
        val DISCOVERED_TYPE: Type = object : TypeToken<Map<String, DiscoveredDevice>>() {}.type
        val CLEANUP_INTERVALS_TYPE: Type = object : TypeToken<Map<String, Int>>() {}.type

        /** Compares folders by labels, uses the folder ID as fallback if the label is empty. */
        val FOLDERS_COMPARATOR = compareBy<Folder> { checkNotNull(it.label.ifEmpty { it.id }) }

        /** The list without its first element matching [predicate]. */
        fun <T> List<T>.withoutFirst(predicate: (T) -> Boolean): List<T> {
            val index = indexOfFirst(predicate)
            return if (index < 0) this else toMutableList().apply { removeAt(index) }
        }
    }
}
