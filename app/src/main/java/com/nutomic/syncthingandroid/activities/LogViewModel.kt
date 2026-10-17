package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.IoDispatcher
import com.nutomic.syncthingandroid.service.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the log screen shows: which log, and its text (null while it is read). */
internal data class LogUiState(val showSyncthingLog: Boolean, val logText: String?)

/**
 * The log screen's state. The logs are read once and kept across a recreation; switching reads both
 * again. Which log is shown survives process death.
 */
@HiltViewModel
internal class LogViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val state =
        MutableStateFlow(
            LogUiState(showSyncthingLog = savedState[SHOW_SYNCTHING_LOG] ?: false, logText = null)
        )
    val uiState: StateFlow<LogUiState> = state.asStateFlow()

    private var fetchJob: Job? = null

    init {
        fetch()
    }

    /** The file of the log shown, for sharing. */
    val shownLogFile: File
        get() =
            if (state.value.showSyncthingLog) Constants.getSyncthingLogFile(context)
            else Constants.getAndroidLogFile(context)

    fun switchLogs() {
        val showSyncthingLog = !state.value.showSyncthingLog
        savedState[SHOW_SYNCTHING_LOG] = showSyncthingLog
        state.update { it.copy(showSyncthingLog = showSyncthingLog) }
        fetch()
    }

    /** Reads both logs again, writing the Android one to its file for sharing, then shows one. */
    private fun fetch() {
        fetchJob?.cancel()
        state.update { it.copy(logText = null) }
        fetchJob = viewModelScope.launch {
            val logs = readLogs(context, ioDispatcher)
            state.update {
                it.copy(logText = if (it.showSyncthingLog) logs.syncthing else logs.android)
            }
        }
    }

    private companion object {
        const val SHOW_SYNCTHING_LOG = "showSyncthingLog"
    }
}
