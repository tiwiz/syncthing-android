package com.nutomic.syncthingandroid.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.ApplicationScope
import com.nutomic.syncthingandroid.IoDispatcher
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.ServiceSnapshot
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.runShellCommandGetOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsViewModels"

/** What a settings screen's ViewModel asks its UI to do once. */
enum class SettingsEvent {
    /** The work is done: leave settings. */
    CLOSE,

    /** The work failed: show the log. */
    OPEN_LOG,
}

/**
 * A settings screen's ViewModel, scoped to its navigation entry: the service comes from
 * [SyncthingConnection], one-off requests go to the UI as [events].
 */
open class SettingsViewModel(connection: SyncthingConnection) : ViewModel() {

    /** The service while bound. The UI collects it, which keeps the binding. */
    val snapshot: StateFlow<ServiceSnapshot> = connection.snapshot

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    protected fun send(event: SettingsEvent) {
        eventChannel.trySend(event)
    }
}

/** Runs the [events] of a settings screen. */
@Composable
fun SettingsEffects(events: Flow<SettingsEvent>, navigator: Navigator<SettingsRoute>) {
    val context = LocalContext.current
    LaunchedEffect(events, navigator) {
        events.collect { event ->
            when (event) {
                SettingsEvent.CLOSE -> navigator.navigateUp()
                SettingsEvent.OPEN_LOG ->
                    context.startActivity(Intent(context, LogActivity::class.java))
            }
        }
    }
}

/** The root list: it only needs to know whether Syncthing runs. */
@HiltViewModel
class SettingsRootViewModel @Inject constructor(connection: SyncthingConnection) :
    SettingsViewModel(connection)

/** Run conditions: the service re-evaluates them once the user is done changing them. */
@HiltViewModel
class RunConditionsViewModel @Inject constructor(connection: SyncthingConnection) :
    SettingsViewModel(connection) {

    private var pendingEvaluation = false

    fun markPending() {
        pendingEvaluation = true
    }

    /** Lets the service apply the changed run conditions, if anything changed since last time. */
    fun evaluatePending() {
        val service = snapshot.value.service ?: return
        if (pendingEvaluation) {
            service.evaluateRunConditions()
            pendingEvaluation = false
        }
    }
}

/** User interface: the app theme also goes to the web GUI. */
@HiltViewModel
class UserInterfaceViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    connection: SyncthingConnection,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsViewModel(connection) {

    /** Applies [theme] to the web GUI. It outlives the screen, like the app theme change. */
    fun syncWebGuiTheme(theme: String) {
        val api = snapshot.value.api
        appScope.launch(ioDispatcher) { syncWebGuiTheme(context, api, theme) }
    }
}

/**
 * Import and export. The work outlives the screen; its result is a toast, then the screen closes
 * (or shows the log on failure).
 */
@HiltViewModel
class ImportExportViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    connection: SyncthingConnection,
    private val preferences: SharedPreferences,
    @ApplicationScope private val appScope: CoroutineScope,
) : SettingsViewModel(connection) {

    fun exportConfig() {
        val service = snapshot.value.service ?: return
        appScope.launch {
            if (suspendCoroutine { done -> service.exportConfig { done.resume(it) } }) {
                toast(R.string.config_export_successful_no_path)
                send(SettingsEvent.CLOSE)
            } else {
                toast(R.string.config_export_failed)
                send(SettingsEvent.OPEN_LOG)
            }
        }
    }

    fun importConfig() {
        val service = snapshot.value.service ?: return
        appScope.launch {
            if (suspendCoroutine { done -> service.importConfig { done.resume(it) } }) {
                toast(R.string.config_imported_successful)
                // Apply the theme from the restored preferences.
                val theme =
                    when (val raw = preferences.all[Constants.PREF_APP_THEME]) {
                        is Int -> raw
                        is String -> raw.toIntOrNull()
                        else -> null
                    } ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppCompatDelegate.setDefaultNightMode(theme)
                service.evaluateRunConditions()
                send(SettingsEvent.CLOSE)
            } else {
                toast(R.string.config_import_failed_no_path)
                send(SettingsEvent.OPEN_LOG)
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
    }
}

/** About: versions and sizes, read again whenever the service changes. */
@HiltViewModel
class AboutViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    connection: SyncthingConnection,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : SettingsViewModel(connection) {

    val state: StateFlow<AboutState> =
        snapshot
            .map { snapshot ->
                val unknown = context.getString(R.string.state_unknown)
                withContext(ioDispatcher) {
                    AboutState(
                        appVersion = getAppVersion(context) ?: unknown,
                        coreVersion = snapshot.api?.version ?: unknown,
                        dbSize = getDatabaseSize(context) ?: unknown,
                        fileLimit = getOpenFileLimit() ?: unknown,
                    )
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SyncthingConnection.STOP_TIMEOUT_MILLIS),
                context.getString(R.string.state_loading).let { AboutState(it, it, it, it) },
            )
}

data class AboutState(
    val appVersion: String = "",
    val coreVersion: String = "",
    val dbSize: String = "",
    val fileLimit: String = "",
)

/**
 * Applies the app theme to the web GUI too, through config.xml or the REST API.
 *
 * It runs fire-and-forget after the app theme has already changed, so whatever goes wrong reading
 * or writing the config (I/O, parsing, REST) is logged and must not crash the app: hence the broad
 * catch.
 */
@Suppress("TooGenericExceptionCaught")
private fun syncWebGuiTheme(context: Context, restApi: RestApi?, newTheme: String) {
    try {
        val config = ConfigRouter(context)
        val gui = config.getGui(restApi)
        gui.theme =
            when (newTheme) {
                AppCompatDelegate.MODE_NIGHT_YES.toString() -> "dark"
                AppCompatDelegate.MODE_NIGHT_NO.toString() -> "light"
                else -> "default"
            }
        config.updateGui(restApi, gui)
    } catch (e: Exception) {
        Log.e(TAG, "Error updating theme with config router", e)
    }
}

private fun getAppVersion(context: Context): String? {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${packageInfo.versionName}"
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(TAG, "Failed to get app version name")
        null
    }
}

private fun getOpenFileLimit(): String? {
    val result = runShellCommandGetOutput("ulimit -n")
    return if (result.isBlank()) null else result.trim()
}

private fun getDatabaseSize(context: Context): String? {
    val dbPath = Constants.getIndexDbFolder(context).absolutePath
    val result = runShellCommandGetOutput("/system/bin/du -sh $dbPath")
    if (result.isBlank()) {
        return null
    }
    // Split by whitespace and grab the first part (the size)
    return result.trim().split(Regex("\\s+")).firstOrNull()
}
