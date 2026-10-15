package com.nutomic.syncthingandroid.views

import android.content.Context
import androidx.annotation.ColorRes
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
    @param:ColorRes @get:ColorRes val stateColor: Int,
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
                    stateColor = R.color.text_red,
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
            val (state, color) =
                stateText(context, folder, folderStatus, cached, outOfSync, showRevert)
            val syncing = folderStatus.state == "syncing"
            return FolderRowState(
                label = label,
                directory = directory,
                type = folder.type,
                state = state,
                stateColor = color,
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
        ): Pair<String, Int> =
            when {
                outOfSync -> context.getString(R.string.status_outofsync) to R.color.text_red
                status.errors > 0 ->
                    context.getString(R.string.state_failed_items, status.errors) to
                        R.color.text_red
                folder.paused -> context.getString(R.string.state_paused) to R.color.text_purple
                else ->
                    when (status.state) {
                        "clean-waiting" ->
                            context.getString(R.string.state_clean_waiting) to R.color.text_orange
                        "cleaning" ->
                            context.getString(R.string.state_cleaning) to R.color.text_blue
                        "idle" ->
                            when {
                                // Idle and not shared with any other device.
                                folder.deviceCount <= 1 ->
                                    context.getString(R.string.state_unshared) to
                                        R.color.text_orange
                                showRevert ->
                                    context.getString(R.string.state_local_additions) to
                                        R.color.text_green
                                else ->
                                    context.getString(R.string.state_up_to_date) to
                                        R.color.text_green
                            }
                        "scan-waiting" ->
                            context.getString(R.string.state_scan_waiting) to R.color.text_orange
                        "scanning" ->
                            context.getString(R.string.state_scanning) to R.color.text_blue
                        "sync-waiting" ->
                            context.getString(R.string.state_sync_waiting) to R.color.text_orange
                        "syncing" ->
                            context.getString(R.string.state_syncing, cached.completion.toInt()) to
                                R.color.text_blue
                        "sync-preparing" ->
                            context.getString(R.string.state_sync_preparing) to R.color.text_blue
                        "error" ->
                            (if (status.error.isEmpty()) context.getString(R.string.state_error)
                            else context.getString(R.string.state_error_message, status.error)) to
                                R.color.text_red
                        "unknown" -> context.getString(R.string.state_unknown) to R.color.text_red
                        else -> status.state to R.color.text_red
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
