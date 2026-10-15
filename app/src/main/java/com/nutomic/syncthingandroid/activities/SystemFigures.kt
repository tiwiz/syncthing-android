package com.nutomic.syncthingandroid.activities

import android.content.Context
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.readableFileSize
import com.nutomic.syncthingandroid.util.readableTransferRate
import java.util.Locale
import java.util.concurrent.TimeUnit

// The lines of the main screen's status tab.

/** What Syncthing reports about itself while it runs, as the status tab shows it. */
internal data class SystemFigures(
    val uptime: String,
    val ramUsage: String,
    val download: String,
    val upload: String,
    /** "connected/total", or "" without announce servers. */
    val announceServer: String,
)

/** Rates below this many bytes per second (discovery, index exchange) show as zero. */
private const val SHOWN_RATE_BYTES = 1024
private const val BITS_PER_BYTE = 8

/** The status tab's figures from a system status and the total of all connections. */
internal fun systemFigures(
    context: Context,
    status: SystemStatus,
    total: Connection,
): SystemFigures {
    val announceTotal = status.discoveryMethods
    val announceConnected = announceTotal - (status.discoveryErrors?.size ?: 0)
    return SystemFigures(
        uptime = readableUptime(status.uptime),
        ramUsage = readableFileSize(context, status.sys.toDouble()),
        download =
            rate(context, total.inBits) +
                " (" +
                readableFileSize(context, total.inBytesTotal.toDouble()) +
                ")",
        upload =
            rate(context, total.outBits) +
                " (" +
                readableFileSize(context, total.outBytesTotal.toDouble()) +
                ")",
        announceServer =
            if (announceTotal == 0) ""
            else
                String.format(Locale.getDefault(), "%1\$d/%2\$d", announceConnected, announceTotal),
    )
}

private fun rate(context: Context, bits: Long): String =
    if (bits / BITS_PER_BYTE < SHOWN_RATE_BYTES) "0 B/s" else readableTransferRate(context, bits)

/** "1d 02h 03m", "2h 03m" or "3m". */
internal fun readableUptime(seconds: Long): String {
    val days = TimeUnit.SECONDS.toDays(seconds)
    val hours = TimeUnit.SECONDS.toHours(seconds) - TimeUnit.DAYS.toHours(days)
    val minutes =
        TimeUnit.SECONDS.toMinutes(seconds) -
            TimeUnit.HOURS.toMinutes(hours) -
            TimeUnit.DAYS.toMinutes(days)
    return when {
        days > 0 -> String.format(Locale.getDefault(), "%dd %02dh %02dm", days, hours, minutes)
        hours > 0 -> String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
        else -> String.format(Locale.getDefault(), "%dm", minutes)
    }
}

/**
 * The status tab's lines: what Syncthing is doing, why it runs or not ([runDecisionExplanation]),
 * and while it runs the [figures] received so far.
 */
internal fun statusLines(
    context: Context,
    state: SyncthingService.State,
    runDecisionExplanation: String,
    figures: SystemFigures?,
): List<String> {
    val lines = mutableListOf<String>()
    lines +=
        context.getString(
            when (state) {
                SyncthingService.State.INIT,
                SyncthingService.State.STARTING -> R.string.syncthing_starting
                SyncthingService.State.ACTIVE -> R.string.syncthing_running
                SyncthingService.State.DISABLED -> R.string.syncthing_not_running
                SyncthingService.State.ERROR -> R.string.syncthing_has_crashed
            }
        )
    if (state == SyncthingService.State.ACTIVE || state == SyncthingService.State.DISABLED) {
        lines +=
            context.getString(R.string.reason) +
                "\n- " +
                runDecisionExplanation.trim().replace("\n", "\n- ")
    }
    if (state == SyncthingService.State.ACTIVE && figures != null) {
        fun add(label: Int, value: String) {
            if (value.isNotEmpty()) lines += context.getString(label) + ": " + value
        }
        add(R.string.uptime, figures.uptime)
        add(R.string.ram_usage, figures.ramUsage)
        add(R.string.download_title, figures.download)
        add(R.string.upload_title, figures.upload)
        add(R.string.announce_server, figures.announceServer)
    }
    return lines
}
