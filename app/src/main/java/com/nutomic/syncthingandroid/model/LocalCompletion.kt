package com.nutomic.syncthingandroid.model

import android.util.Log
import com.google.gson.Gson
import java.util.AbstractMap.SimpleEntry
import kotlin.math.floor

/**
 * Caches local folder synchronization completion indicators defined in [CachedFolderStatus],
 * according to Syncthing's "FolderSummary" event JSON result schema. Completion model of
 * Syncthing's web UI is completion[folderId].
 */
class LocalCompletion(private val enableVerboseLog: Boolean) {

    /** Guarded by itself. */
    private val folderMap = HashMap<String?, Map.Entry<FolderStatus, CachedFolderStatus>>()

    /** Updates folder information in the cache model after a config update. */
    fun updateFromConfig(newFolders: List<Folder>) {
        synchronized(folderMap) {
            // Handle folders that were removed from the config.
            val removedFolders = folderMap.keys.filter { id -> newFolders.none { it.id == id } }
            for (folderId in removedFolders) {
                logV("updateFromConfig: Remove folder '$folderId' from cache model")
                folderMap.remove(folderId)
            }
            // Handle folders that were added to the config.
            for (folder in newFolders) {
                val id = folder.id
                if (!folderMap.containsKey(id)) {
                    logV("updateFromConfig: Add folder '$id' to cache model.")
                    folderMap[id] = SimpleEntry(FolderStatus(), CachedFolderStatus())
                }
            }
        }
    }

    /** Local folder sync completion percentage across all folders. */
    val totalFolderCompletion: Int
        get() =
            synchronized(folderMap) {
                var folderCount = 0
                var sumCompletion = 0.0
                for (entry in folderMap.values) {
                    val cachedFolderStatus = entry.value
                    // Filter invalid percentage values we may have got from the REST API.
                    cachedFolderStatus.completion =
                        cachedFolderStatus.completion.coerceIn(0.0, COMPLETE_PERCENT)
                    if (
                        !cachedFolderStatus.paused &&
                            cachedFolderStatus.completion != COMPLETE_PERCENT
                    ) {
                        sumCompletion += cachedFolderStatus.completion
                        folderCount++
                    }
                }
                if (folderCount == 0) {
                    COMPLETE
                } else {
                    floor(sumCompletion / folderCount).toInt().coerceIn(0, COMPLETE)
                }
            }

    /** Local folder status including completion info, as a copy. */
    fun getFolderStatus(folderId: String?): Map.Entry<FolderStatus, CachedFolderStatus> =
        synchronized(folderMap) {
            val folderEntry =
                folderMap[folderId] ?: return SimpleEntry(FolderStatus(), CachedFolderStatus())
            SimpleEntry(
                deepCopy(folderEntry.key, FolderStatus::class.java),
                deepCopy(folderEntry.value, CachedFolderStatus::class.java),
            )
        }

    /**
     * Stores folderStatus for later when we need info for the UI, and calculates the
     * cachedFolderStatus within the completion[folderId] model.
     */
    fun setFolderStatus(folderId: String?, folderPaused: Boolean, folderStatus: FolderStatus) {
        synchronized(folderMap) {
            val cachedFolderStatus = getFolderStatus(folderId).value
            cachedFolderStatus.paused = folderPaused
            cachedFolderStatus.completion =
                if (
                    folderStatus.globalBytes == 0L ||
                        folderStatus.inSyncBytes > folderStatus.globalBytes
                ) {
                    COMPLETE_PERCENT
                } else {
                    floor(folderStatus.inSyncBytes.toDouble() / folderStatus.globalBytes * COMPLETE)
                        .toInt()
                        .toDouble()
                }
            if (folderStatus.state == "idle") {
                cachedFolderStatus.completion = COMPLETE_PERCENT
            }
            logV(
                "setFolderStatus: folderId=\"$folderId\", state=\"${folderStatus.state}\"" +
                    ", paused=${cachedFolderStatus.paused}" +
                    ", completion=${cachedFolderStatus.completion.toInt()}%"
            )
            // Add folder or update existing folder entry.
            folderMap[folderId] = SimpleEntry(folderStatus, cachedFolderStatus)
        }
    }

    /** Like the other overload, keeping the paused flag of the previous entry. */
    fun setFolderStatus(folderId: String?, folderStatus: FolderStatus) {
        synchronized(folderMap) {
            setFolderStatus(folderId, getFolderStatus(folderId).value.paused, folderStatus)
        }
    }

    /**
     * Setters of additionally stored information, e.g. "ItemFinished" event details arriving
     * through EventProcessor and RestApi.
     */
    fun setLastItemFinished(
        folderId: String?,
        lastItemFinishedAction: String,
        lastItemFinishedItem: String?,
        lastItemFinishedTime: String?,
    ) =
        updateCached(folderId) {
            it.lastItemFinishedAction = lastItemFinishedAction
            it.lastItemFinishedItem = lastItemFinishedItem
            it.lastItemFinishedTime = lastItemFinishedTime
        }

    fun setRemoteIndexUpdated(folderId: String?, remoteIndexUpdated: Boolean) =
        updateCached(folderId) { it.remoteIndexUpdated = remoteIndexUpdated }

    fun setDiscoveredConflictFiles(folderId: String?, discoveredConflictFiles: Array<String>) =
        updateCached(folderId) { it.discoveredConflictFiles = discoveredConflictFiles }

    /** Applies [change] to a copy of the folder's cached status and stores it. */
    private fun updateCached(folderId: String?, change: (CachedFolderStatus) -> Unit) {
        synchronized(folderMap) {
            val cacheEntry = getFolderStatus(folderId)
            change(cacheEntry.value)
            // Add folder or update existing folder entry.
            folderMap[folderId] = SimpleEntry(cacheEntry.key, cacheEntry.value)
        }
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    private companion object {
        const val TAG = "LocalCompletion"

        /** A deep copy through Gson; only works with objects Gson can convert. */
        fun <T> deepCopy(value: T, type: Class<T>): T = Gson().run { fromJson(toJson(value), type) }
    }
}
