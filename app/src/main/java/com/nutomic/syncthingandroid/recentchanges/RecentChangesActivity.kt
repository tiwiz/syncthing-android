package com.nutomic.syncthingandroid.recentchanges

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.ThemedAppCompatActivity
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DiskEvent
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.FileOpener
import java.io.File
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shows the list of recent changes to files and folders reported by the local Syncthing instance.
 */
@AndroidEntryPoint
class RecentChangesActivity : ThemedAppCompatActivity() {

    private val viewModel: RecentChangesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ApplicationTheme {
                RecentChangesScreen(
                    changes = state.changes,
                    isRefreshing = state.isRefreshing,
                    showExactTimes = state.showExactTimes,
                    onNavigateBack = { finish() },
                    onRefresh = { viewModel.refresh() },
                    onToggleExactTimes = viewModel::toggleExactTimes,
                    onItemClick = ::openChange,
                )
            }
        }

        // `repeatOnLifecycle(STARTED)` stops polling when the screen is not visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileShown() }
        }
    }

    private fun openChange(change: RecentChange) {
        if (!change.canOpen) return
        Log.v(TAG, "User clicked item with title '${change.rawPath}'")
        val fullPath = viewModel.fullPathOf(change) ?: return
        when (change.type) {
            ChangeType.DIR -> FileOpener.openFolder(this, fullPath)
            ChangeType.FILE -> FileOpener.openFile(this, fullPath)
            ChangeType.UNKNOWN -> Log.e(TAG, "openChange: unknown type for path=[${change.rawPath}]")
        }
    }

    private companion object {
        const val TAG = "RecentChangesActivity"
    }
}

private val REQUEST_TIMEOUT = 30.seconds

/**
 * Suspending wrapper around [RestApi.getDiskEvents].
 *
 * Returns null if the request does not finish within [REQUEST_TIMEOUT]. The timeout is not optional:
 * `ApiRequest.connect` passes an error listener that swallows failures (`error -> {}`), so a failed
 * request never invokes the success callback and would otherwise leave this coroutine suspended
 * forever.
 *
 * ### Why the continuation is held behind an [AtomicReference]
 *
 * Cancelling this coroutine (e.g. the user presses back, destroying the Activity and its
 * `lifecycleScope`) does **not** cancel the underlying HTTP request. `ApiRequest.connect` adds the
 * request to a `static` Volley queue and keeps no handle to it, and `getDiskEvents` returns nothing
 * we could cancel with. The request therefore runs to completion and its result is discarded.
 *
 * That matters because Volley keeps the success listener alive until the request finishes, and a
 * listener capturing the continuation directly would retain this chain:
 *
 * ```
 * static sVolleyQueue -> StringRequest -> listener -> continuation -> coroutine
 *     -> lifecycleScope -> Activity (and its whole Compose tree)
 * ```
 *
 * With `DefaultRetryPolicy(5000, 5, DEFAULT_BACKOFF_MULT)` that is up to ~30s (5s × 6 attempts) of a
 * destroyed Activity being unreachable-but-retained. Clearing the reference on cancellation breaks
 * the chain immediately, so the Activity is collectable as soon as it is destroyed. The `getAndSet`
 * also guarantees the continuation is resumed at most once, whichever thread delivers first.
 *
 * **This only fixes the retention, not the wasted work.** Genuinely aborting the request would mean
 * plumbing a Volley request tag through `ApiRequest`/`GetRequest`/`RestApi` so the queue could
 * `cancelAll(tag)` — shared code used by every screen — and even then Volley's cancel only suppresses
 * *delivery*, it does not abort an in-flight socket. Since this is a loopback call to the local
 * Syncthing process it normally returns in milliseconds, so that was judged not worth the blast
 * radius. Revisit if these requests ever go off-device.
 */
internal suspend fun RestApi.awaitDiskEvents(limit: Int): List<DiskEvent>? =
    withTimeoutOrNull(REQUEST_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val pending = AtomicReference(continuation)
            continuation.invokeOnCancellation { pending.set(null) }
            getDiskEvents(limit) { diskEvents -> pending.getAndSet(null)?.resume(diskEvents) }
        }
    }

enum class ChangeType {
    FILE, DIR, UNKNOWN;

    companion object {
        fun of(raw: String?) = when (raw) {
            "file" -> FILE
            "dir" -> DIR
            else -> UNKNOWN
        }
    }
}

enum class ChangeAction {
    ADDED, DELETED, MODIFIED, UNKNOWN;

    companion object {
        fun of(raw: String?) = when (raw) {
            "added" -> ADDED
            "deleted" -> DELETED
            "modified" -> MODIFIED
            else -> UNKNOWN
        }
    }
}

/** Immutable UI model for a single row of [RecentChangesScreen]. */
@Immutable
data class RecentChange(
    val filename: String,
    val folderPath: String,
    val modifiedByName: String,
    /** Epoch millis of the change, or null if the reported timestamp could not be parsed. */
    val timeMillis: Long?,
    /** The timestamp exactly as Syncthing reported it; shown when [timeMillis] is null. */
    val rawTime: String,
    val type: ChangeType,
    val action: ChangeAction,
    /** Needed to resolve the on-disk location when the row is opened. */
    val folderId: String,
    val rawPath: String,
) {
    /** Deleted entries are gone from disk, and an unknown type has no viewer to launch. */
    val canOpen: Boolean get() = action != ChangeAction.DELETED && type != ChangeType.UNKNOWN
}

internal fun List<DiskEvent>.toRecentChanges(
    devices: List<Device>,
    localDeviceId: String,
    thisDeviceLabel: String,
): List<RecentChange> {
    // Reused across the whole list rather than rebuilt per row. SimpleDateFormat is not thread-safe,
    // which is fine: this only ever runs on the main dispatcher.
    val timestampParser = SimpleDateFormat(RFC3339_PATTERN, Locale.US)
    return withoutUselessEvents().map { event ->
        RecentChange(
            filename = fileNameOf(event.data.path),
            folderPath = "[${event.data.label}]${File.separator}${parentPathOf(event.data.path)}",
            modifiedByName = resolveDeviceName(event.data.modifiedBy, devices, localDeviceId, thisDeviceLabel),
            timeMillis = timestampParser.parseEpochMillisOrNull(event.time),
            rawTime = event.time,
            type = ChangeType.of(event.data.type),
            action = ChangeAction.of(event.data.action),
            folderId = event.data.folderID,
            rawPath = event.data.path,
        )
    }
}

private const val RFC3339_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ"

/**
 * Parses a Syncthing timestamp (RFC 3339, e.g. `2018-10-29T15:18:52.6183215+01:00`) to epoch millis,
 * or null if it does not parse.
 *
 * `SimpleDateFormat` is used rather than `java.time` because minSdk is 23 and core library desugaring
 * is not enabled, so `OffsetDateTime` would be unavailable on API 23–25. It needs the input normalised
 * first: the fractional seconds dropped (it cannot handle 7–9 digits), and the offset reduced from
 * `+01:00` to the RFC 822 `+0100` that the `Z` pattern expects.
 */
private fun SimpleDateFormat.parseEpochMillisOrNull(raw: String): Long? {
    if (raw.isEmpty()) return null
    val normalised = raw
        .replace(FRACTIONAL_SECONDS, "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
        .replace(OFFSET_WITH_COLON, "$1$2")
    return runCatching { parse(normalised)?.time }.getOrNull()
}

private val FRACTIONAL_SECONDS = Regex("\\.\\d+")
private val OFFSET_WITH_COLON = Regex("([+-]\\d{2}):(\\d{2})$")

/**
 * Drops disk events that are not useful to display:
 *  - events carrying no data,
 *  - an "added" entry whose exact path is deleted by a later event,
 *  - any entry inside a directory that a later event deletes.
 *
 * Later means "has a higher event id". Deletions are indexed up front so this stays linear in the
 * number of events (times path depth) instead of comparing every event against every other.
 *
 */
internal fun List<DiskEvent>.withoutUselessEvents(): List<DiskEvent> {
    val valid = filter { it.data != null }

    val lastDeletedAt = HashMap<String, Long>()
    val lastDirDeletedAt = HashMap<String, Long>()
    for (event in valid) {
        if (ChangeAction.of(event.data.action) != ChangeAction.DELETED) continue
        lastDeletedAt.keepHighest(event.data.path, event.id)
        if (ChangeType.of(event.data.type) == ChangeType.DIR) {
            lastDirDeletedAt.keepHighest(event.data.path, event.id)
        }
    }
    if (lastDeletedAt.isEmpty()) return valid

    fun deletedAfter(map: Map<String, Long>, path: String, id: Long) = (map[path] ?: -1L) > id

    return valid.filterNot { event ->
        val path = event.data.path
        val supersededByDelete = ChangeAction.of(event.data.action) == ChangeAction.ADDED &&
            deletedAfter(lastDeletedAt, path, event.id)

        supersededByDelete ||
            path.ancestorPaths().any { deletedAfter(lastDirDeletedAt, it, event.id) }
    }
}

/**
 * Stores [value] under [key] unless a higher value is already recorded.
 *
 * Hand-rolled because the obvious alternatives — `merge`, `compute`, `putIfAbsent`, `getOrDefault` —
 * are all Java 8 default methods that require API 24, and minSdk is 23.
 */
private fun MutableMap<String, Long>.keepHighest(key: String, value: Long) {
    val current = this[key]
    if (current == null || value > current) {
        this[key] = value
    }
}

/** Ancestor directory paths, longest first: "a/b/c.txt" -> "a/b", "a". */
private fun String.ancestorPaths(): Sequence<String> = sequence {
    var slash = lastIndexOf('/')
    while (slash > 0) {
        yield(substring(0, slash))
        slash = lastIndexOf('/', slash - 1)
    }
}

/** Last path segment, e.g. "a/b/c.txt" -> "c.txt". */
private fun fileNameOf(path: String): String {
    // Encode "#" so Uri.parse handles it. See syncthing-android/651.
    val uriParseInput = path.replace("#", Uri.encode("#"))
    return uriParseInput.toUri().lastPathSegment.orEmpty()
}

/** Parent directory of a path, e.g. "a/b/c.txt" -> "a/b", "c.txt" -> "". */
private fun parentPathOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

/**
 * Resolves the partial device ID reported by Syncthing to a readable device name, or
 * [thisDeviceLabel] for the local device. Returns the input unchanged if it is blank or matches no
 * known device.
 */
private fun resolveDeviceName(
    modifiedBy: String,
    devices: List<Device>,
    localDeviceId: String,
    thisDeviceLabel: String,
): String {
    if (modifiedBy.isEmpty()) return modifiedBy
    val device = devices.firstOrNull { checkNotNull(it.deviceID).startsWith(modifiedBy) } ?: return modifiedBy
    return if (device.deviceID == localDeviceId) thisDeviceLabel else device.displayName
}
