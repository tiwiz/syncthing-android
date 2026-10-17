package com.nutomic.syncthingandroid.recentchanges

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants.PREF_SHOW_EXACT_TIMES
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.ServiceSnapshot
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.service.SyncthingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the recent changes screen shows. */
internal data class RecentChangesUiState(
    val changes: List<RecentChange> = emptyList(),
    /** Drives the pull-to-refresh indicator; only set for refreshes the user asked for. */
    val isRefreshing: Boolean = false,
    val showExactTimes: Boolean = false,
)

/**
 * The recent changes screen's state. The list is kept across a recreation; it is fetched when
 * Syncthing becomes active, when the user pulls, and on an interval while the screen is shown.
 */
@HiltViewModel
internal class RecentChangesViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SharedPreferences,
    connection: SyncthingConnection,
) : ViewModel() {

    private val state =
        MutableStateFlow(
            RecentChangesUiState(
                showExactTimes = preferences.getBoolean(PREF_SHOW_EXACT_TIMES, false)
            )
        )
    val uiState: StateFlow<RecentChangesUiState> = state.asStateFlow()

    private var snapshot = ServiceSnapshot.UNBOUND

    /** Re-entrancy guard, separate from `isRefreshing` so background polls stay invisible. */
    private var refreshInFlight = false

    init {
        viewModelScope.launch {
            connection.snapshot.collect { new ->
                val becameActive =
                    new.state == SyncthingService.State.ACTIVE && new.state != snapshot.state
                snapshot = new
                Log.v(TAG, "onServiceStateChange(${new.state})")
                if (becameActive) {
                    refresh()
                }
            }
        }
    }

    /**
     * Keeps the list current while the screen is in the foreground, without ever showing the
     * refresh indicator — new changes just appear.
     *
     * This polls on an interval rather than holding a long-poll open (which is how the web GUI
     * stays live). Two things make a subscription impractical here: `RestApi.getDiskEvents` has no
     * `since` parameter, so a loop would re-fetch the same buffered events and spin as fast as the
     * server could answer; and Volley's `DefaultRetryPolicy` uses a 5s socket timeout, so it
     * abandons and retries a blocked long-poll several times per call — the server explicitly notes
     * such requests "should not be retried". Short `timeout=1` queries on a timer are far more
     * predictable.
     */
    suspend fun pollWhileShown() {
        while (true) {
            delay(POLL_INTERVAL)
            refresh(showProgress = false)
        }
    }

    /**
     * @param showProgress whether to drive the refresh indicator. False for background polls, so
     *   they update the list silently.
     */
    fun refresh(showProgress: Boolean = true) {
        if (refreshInFlight) return
        val api = activeApi ?: return

        refreshInFlight = true
        if (showProgress) state.update { it.copy(isRefreshing = true) }
        // A pending request is dropped if the screen goes away.
        viewModelScope.launch {
            try {
                // Both of these are cached config reads, not network calls.
                val devices = api.getDevices(true)
                val localDeviceId = api.localDevice.deviceID
                Log.v(TAG, "Querying disk events")
                api.awaitDiskEvents(DISK_EVENT_LIMIT)?.let { diskEvents ->
                    val changes =
                        diskEvents.toRecentChanges(
                            devices,
                            checkNotNull(localDeviceId),
                            context.getString(R.string.this_device),
                        )
                    state.update { it.copy(changes = changes) }
                }
            } finally {
                refreshInFlight = false
                state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun toggleExactTimes() {
        val showExactTimes = !state.value.showExactTimes
        state.update { it.copy(showExactTimes = showExactTimes) }
        preferences.edit { putBoolean(PREF_SHOW_EXACT_TIMES, showExactTimes) }
    }

    /** The full path of [change] on this device, or null if its folder is unavailable. */
    fun fullPathOf(change: RecentChange): String? {
        val folder = activeApi?.getFolderByID(change.folderId)
        if (folder == null) {
            Log.e(TAG, "openChange: folder unavailable for id=[${change.folderId}]")
            return null
        }
        return folder.path + File.separator + change.rawPath
    }

    /** The [RestApi], but only once the service is actually running. */
    private val activeApi: RestApi?
        get() = snapshot.api?.takeIf { snapshot.state == SyncthingService.State.ACTIVE }

    private companion object {
        const val TAG = "RecentChangesActivity"
        const val DISK_EVENT_LIMIT = 100

        /** How often the foreground screen re-queries. Tune freely; each query is a local call. */
        val POLL_INTERVAL = 5.seconds
    }
}
