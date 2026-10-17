package com.nutomic.syncthingandroid.activities

import android.content.Context
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.readableFileSize
import com.nutomic.syncthingandroid.util.readableTransferRate
import com.nutomic.syncthingandroid.views.StateTone
import java.util.Locale
import java.util.concurrent.TimeUnit

// What the main screen's status tab shows.

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

/** What the status tab shows: what Syncthing does, why, and while it runs its figures. */
internal data class StatusInfo(
    val headline: String,
    val tone: StateTone,
    /** Whether Syncthing is starting: a loading indicator replaces the state badge. */
    val starting: Boolean,
    /** Why Syncthing runs or not, one per item; none while it starts or after a crash. */
    val reasons: List<String>,
    /** Label and value of each figure, only while it runs. */
    val figures: List<Pair<String, String>>,
)

/** The status tab's blocks; [runDecisionExplanation] has one reason per line. */
internal fun statusInfo(
    context: Context,
    state: SyncthingService.State,
    runDecisionExplanation: String,
    figures: SystemFigures?,
): StatusInfo {
    val (headline, tone) =
        when (state) {
            SyncthingService.State.INIT,
            SyncthingService.State.STARTING -> R.string.syncthing_starting to StateTone.BUSY
            SyncthingService.State.ACTIVE -> R.string.syncthing_running to StateTone.OK
            SyncthingService.State.DISABLED -> R.string.syncthing_not_running to StateTone.OFFLINE
            SyncthingService.State.ERROR -> R.string.syncthing_has_crashed to StateTone.ERROR
        }
    val explained =
        state == SyncthingService.State.ACTIVE || state == SyncthingService.State.DISABLED
    val shown =
        if (state == SyncthingService.State.ACTIVE && figures != null) {
            listOf(
                    R.string.uptime to figures.uptime,
                    R.string.ram_usage to figures.ramUsage,
                    R.string.download_title to figures.download,
                    R.string.upload_title to figures.upload,
                    R.string.announce_server to figures.announceServer,
                )
                .filter { it.second.isNotEmpty() }
                .map { context.getString(it.first) to it.second }
        } else {
            emptyList()
        }
    return StatusInfo(
        headline = context.getString(headline),
        tone = tone,
        starting = state == SyncthingService.State.STARTING,
        reasons = if (explained) runDecisionExplanation.trim().split("\n") else emptyList(),
        figures = shown,
    )
}

/** The status tab as lines of text: the headline, "Reason:" with the reasons, the figures. */
internal fun statusLines(
    context: Context,
    state: SyncthingService.State,
    runDecisionExplanation: String,
    figures: SystemFigures?,
): List<String> {
    val info = statusInfo(context, state, runDecisionExplanation, figures)
    val reasons =
        if (info.reasons.isEmpty()) emptyList()
        else listOf(context.getString(R.string.reason) + "\n- " + info.reasons.joinToString("\n- "))
    return listOf(info.headline) + reasons + info.figures.map { "${it.first}: ${it.second}" }
}
