package com.nutomic.syncthingandroid.views

import android.content.Context
import androidx.annotation.StringRes
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.CachedFolderStatus
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.formatTime
import com.nutomic.syncthingandroid.util.getPathEllipsis
import com.nutomic.syncthingandroid.util.readableFileSize

/**
 * What a folder's row in the folder list shows: always its label and directory, and with Syncthing
 * running its state, counts, conflicts, last synced item and the actions it offers.
 */
data class FolderRowState(
    val label: String,
    val directory: String,
    val type: String,
    /** Null while Syncthing is not running. */
    val state: String?,
    val tone: StateTone,
    val showOverride: Boolean,
    val showRevert: Boolean,
    @param:StringRes @get:StringRes val revertLabel: Int,
    /** The sync progress in percent, or null when not syncing. */
    val progress: Int?,
    val conflicts: String?,
    val lastItemFinished: String?,
    val lastItemFinishedTime: String?,
    /** Files and sizes, or null when paused or not running. */
    val items: String?,
    val invalid: String?,
) {
    companion object {
        /** The row of [folder]; [status] is null while Syncthing is not running. */
        @JvmStatic
        fun of(
            context: Context,
            folder: Folder,
            status: Map.Entry<FolderStatus, CachedFolderStatus>?,
        ): FolderRowState {
            val label = folder.label.ifEmpty { folder.id.orEmpty() }
            val directory = shortPathForUi(context, folder.path.orEmpty())
            if (status == null) {
                return FolderRowState(
                    label = label,
                    directory = directory,
                    type = folder.type,
                    state = null,
                    tone = StateTone.OFFLINE,
                    showOverride = false,
                    showRevert = false,
                    revertLabel = R.string.revert_local_changes,
                    progress = null,
                    conflicts = null,
                    lastItemFinished = null,
                    lastItemFinishedTime = null,
                    items = null,
                    invalid = folder.invalid?.ifEmpty { null },
                )
            }
            val folderStatus = status.key
            val cached = status.value
            val neededItems =
                folderStatus.needFiles +
                    folderStatus.needDirectories +
                    folderStatus.needSymlinks +
                    folderStatus.needDeletes
            val outOfSync = folderStatus.state == "idle" && neededItems > 0
            val showRevert =
                when (folder.type) {
                    Constants.FOLDER_TYPE_RECEIVE_ONLY -> folderStatus.receiveOnlyTotalItems > 0
                    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED ->
                        folderStatus.receiveOnlyTotalItems -
                            folderStatus.receiveOnlyChangedDeletes > 0
                    else -> false
                }
            val (state, tone) =
                stateText(context, folder, folderStatus, cached, outOfSync, showRevert)
            val syncing = folderStatus.state == "syncing"
            return FolderRowState(
                label = label,
                directory = directory,
                type = folder.type,
                state = state,
                tone = tone,
                showOverride = folder.type == Constants.FOLDER_TYPE_SEND_ONLY && outOfSync,
                showRevert = showRevert,
                revertLabel =
                    if (folder.type == Constants.FOLDER_TYPE_RECEIVE_ONLY)
                        R.string.revert_local_changes
                    else R.string.delete_unexpected_items,
                progress = if (syncing) cached.completion.toInt() else null,
                conflicts = conflictsText(context, cached.discoveredConflictFiles),
                lastItemFinished = lastItemText(cached),
                lastItemFinishedTime =
                    lastItemText(cached)?.let {
                        "⇌⌚" + formatTime(checkNotNull(cached.lastItemFinishedTime))
                    },
                items = if (folder.paused) null else itemsText(context, folderStatus),
                invalid = folderStatus.invalid.ifEmpty { null },
            )
        }

        @Suppress("CyclomaticComplexMethod", "LongParameterList") // One branch per Syncthing state.
        private fun stateText(
            context: Context,
            folder: Folder,
            status: FolderStatus,
            cached: CachedFolderStatus,
            outOfSync: Boolean,
            showRevert: Boolean,
        ): Pair<String, StateTone> =
            when {
                outOfSync -> context.getString(R.string.status_outofsync) to StateTone.ERROR
                status.errors > 0 ->
                    context.getString(R.string.state_failed_items, status.errors) to StateTone.ERROR
                folder.paused -> context.getString(R.string.state_paused) to StateTone.PAUSED
                else ->
                    when (status.state) {
                        "clean-waiting" ->
                            context.getString(R.string.state_clean_waiting) to StateTone.ATTENTION
                        "cleaning" -> context.getString(R.string.state_cleaning) to StateTone.BUSY
                        "idle" ->
                            when {
                                // Idle and not shared with any other device.
                                folder.deviceCount <= 1 ->
                                    context.getString(R.string.state_unshared) to
                                        StateTone.ATTENTION
                                showRevert ->
                                    context.getString(R.string.state_local_additions) to
                                        StateTone.OK
                                else -> context.getString(R.string.state_up_to_date) to StateTone.OK
                            }
                        "scan-waiting" ->
                            context.getString(R.string.state_scan_waiting) to StateTone.ATTENTION
                        "scanning" -> context.getString(R.string.state_scanning) to StateTone.BUSY
                        "sync-waiting" ->
                            context.getString(R.string.state_sync_waiting) to StateTone.ATTENTION
                        "syncing" ->
                            context.getString(R.string.state_syncing, cached.completion.toInt()) to
                                StateTone.BUSY
                        "sync-preparing" ->
                            context.getString(R.string.state_sync_preparing) to StateTone.BUSY
                        "error" ->
                            (if (status.error.isEmpty()) context.getString(R.string.state_error)
                            else context.getString(R.string.state_error_message, status.error)) to
                                StateTone.ERROR
                        "unknown" -> context.getString(R.string.state_unknown) to StateTone.ERROR
                        else -> status.state to StateTone.ERROR
                    }
            }

        private fun conflictsText(context: Context, files: Array<String>): String? {
            if (files.isEmpty()) return null
            var text =
                "⚠ " +
                    context.resources.getQuantityString(R.plurals.conflicts, files.size, files.size)
            text += "\n⤮ " + files[0]
            if (files.size > 1) text += "\n…"
            return text
        }

        private fun lastItemText(cached: CachedFolderStatus): String? {
            if (
                cached.lastItemFinishedAction.isEmpty() ||
                    cached.lastItemFinishedItem.isNullOrEmpty() ||
                    cached.lastItemFinishedTime.isNullOrEmpty()
            ) {
                return null
            }
            val symbol =
                when (cached.lastItemFinishedAction) {
                    "delete" -> " ⊗" // (x)
                    "update" -> " ⊛" // (*)
                    else -> " ⁉" // !?
                }
            return "⇌" + symbol + " " + getPathEllipsis(checkNotNull(cached.lastItemFinishedItem))
        }

        private fun itemsText(context: Context, status: FolderStatus): String =
            "∑ " +
                context.resources.getQuantityString(
                    R.plurals.files,
                    status.inSyncFiles.toInt(),
                    status.inSyncFiles,
                    status.globalFiles,
                ) +
                " • " +
                context.getString(
                    R.string.folder_size_format,
                    readableFileSize(context, status.inSyncBytes.toDouble()),
                    readableFileSize(context, status.globalBytes.toDouble()),
                )

        /** The path with "[int]", "[ext]" and "[app]" for the long parts, ellipsized. */
        private fun shortPathForUi(context: Context, path: String): String {
            val shortened =
                path
                    .replaceFirst("/storage/emulated/0", "[int]")
                    .replaceFirst(Regex("/storage/[a-zA-Z0-9]{4}-[a-zA-Z0-9]{4}"), "[ext]")
                    .replaceFirst("/" + context.packageName, "/[app]")
            return "❖ " + getPathEllipsis(shortened)
        }
    }
}
