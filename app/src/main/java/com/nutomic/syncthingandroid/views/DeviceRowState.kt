package com.nutomic.syncthingandroid.views

import android.content.Context
import androidx.annotation.ColorRes
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.util.formatDateTime
import com.nutomic.syncthingandroid.util.readableFileSize
import com.nutomic.syncthingandroid.util.readableTransferRate

/**
 * What a device's row in the device list shows: its name, when it was last seen, the folders it
 * shares, and its connection and sync state.
 */
data class DeviceRowState(
    val name: String,
    val lastSeen: String,
    val sharedFoldersTitle: String,
    /** One folder per line, or null when it shares none. */
    val sharedFolders: String?,
    val status: String,
    val tone: StateTone,
    /** The sync progress in percent, or null when not syncing. */
    val progress: Int?,
    /** Download and upload rates, or null when not connected. */
    val bandwidth: String?,
) {
    /** The color of [status]. */
    @get:ColorRes
    val statusColor: Int
        get() = tone.textColor

    /** What Syncthing knows about the device while it runs. */
    class Status(val connection: Connection, val completion: Int, val needBytes: Double)

    companion object {
        /** The timestamp Syncthing reports for devices it never saw. */
        private const val TIMESTAMP_NEVER_SEEN = "1970-01-01T00:00:00Z"

        /** Traffic above this, in bits per second, means a device at 100% is still syncing. */
        private const val ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD = 50L * 1024 * 8

        private const val COMPLETE = 100

        /**
         * The row of [device], last seen at [lastSeen] ("" if unknown) and sharing [sharedFolders];
         * [status] is null while Syncthing is not running.
         */
        @JvmStatic
        fun of(
            context: Context,
            device: Device,
            lastSeen: String,
            sharedFolders: List<Folder>,
            status: Status?,
        ): DeviceRowState {
            val base =
                DeviceRowState(
                    name = device.displayName,
                    lastSeen =
                        context.getString(
                            R.string.device_last_seen,
                            if (lastSeen.isEmpty() || lastSeen == TIMESTAMP_NEVER_SEEN) {
                                context.getString(R.string.device_last_seen_never)
                            } else {
                                formatDateTime(lastSeen)
                            },
                        ),
                    sharedFoldersTitle =
                        context.getString(
                            if (sharedFolders.isEmpty()) R.string.device_state_unused
                            else R.string.shared_folders_title_colon
                        ),
                    sharedFolders =
                        if (sharedFolders.isEmpty()) null
                        else "• " + sharedFolders.joinToString("\n• "),
                    status = context.getString(R.string.device_disconnected),
                    tone = StateTone.OFFLINE,
                    progress = null,
                    bandwidth = null,
                )
            return when {
                device.paused ->
                    base.copy(
                        status = context.getString(R.string.device_paused),
                        tone = StateTone.PAUSED,
                    )
                // Syncthing is not running.
                status == null -> base
                status.connection.connected -> connected(context, base, status)
                status.needBytes == 0.0 -> base
                else ->
                    base.copy(
                        status =
                            context.getString(
                                R.string.device_disconnected_not_synced,
                                readableFileSize(context, status.needBytes),
                            )
                    )
            }
        }

        private fun connected(
            context: Context,
            base: DeviceRowState,
            status: Status,
        ): DeviceRowState {
            val conn = status.connection
            val bandwidth =
                "⇵ " + // down+up arrow
                    context.getString(R.string.download_title) +
                    " ˅ " + // down arrow
                    readableTransferRate(context, conn.inBits) +
                    " • " + // dot
                    context.getString(R.string.upload_title) +
                    " ˄ " + // up arrow
                    readableTransferRate(context, conn.outBits)
            if (status.completion != COMPLETE) {
                return base.copy(
                    status =
                        context.getString(
                            R.string.device_syncing_percent_bytes,
                            status.completion,
                            readableFileSize(context, status.needBytes),
                        ),
                    tone = StateTone.BUSY,
                    progress = status.completion,
                    bandwidth = bandwidth,
                )
            }
            // 100% is also the initial value while data is still flowing: traffic tells them apart.
            val syncing = conn.inBits + conn.outBits >= ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD
            return base.copy(
                status =
                    context.getString(
                        if (syncing) R.string.state_syncing_general else R.string.device_up_to_date
                    ),
                tone = if (syncing) StateTone.BUSY else StateTone.OK,
                bandwidth = bandwidth,
            )
        }
    }
}
