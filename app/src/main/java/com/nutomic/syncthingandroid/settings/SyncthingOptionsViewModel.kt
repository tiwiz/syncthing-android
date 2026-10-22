package com.nutomic.syncthingandroid.settings

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.google.common.base.Splitter
import com.nutomic.syncthingandroid.ApplicationScope
import com.nutomic.syncthingandroid.IoDispatcher
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.ServiceSnapshot
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.MapPreferences
import me.zhanghai.compose.preference.Preferences
import org.mindrot.jbcrypt.BCrypt

private const val TAG = "SyncthingOptionsViewModel"

private const val BIND_ALL = "0.0.0.0"
private const val BIND_LOCALHOST = "127.0.0.1"

/** Whether Syncthing runs with its config loaded, so its options can be read and changed. */
val ServiceSnapshot.optionsAvailable: Boolean
    get() = state == SyncthingService.State.ACTIVE && api?.isConfigLoaded == true

enum class SupportBundleDownloadState {
    INIT,
    DOWNLOADING,
    SUCCESS,
    FAILED,
}

data class SupportBundle(
    val state: SupportBundleDownloadState = SupportBundleDownloadState.INIT,
    val fileName: String = "",
)

/**
 * Syncthing's own options, edited as [apiPreferences] and written back half a second after the last
 * change, plus the maintenance actions. The actions outlive the screen; they report with a toast.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SyncthingOptionsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    connection: SyncthingConnection,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsViewModel(connection) {

    val apiPreferences = MutableStateFlow<Preferences>(MapPreferences())

    private val supportBundleState = MutableStateFlow(SupportBundle())
    val supportBundle: StateFlow<SupportBundle> = supportBundleState.asStateFlow()

    init {
        viewModelScope.launch {
            snapshot
                .map { snapshot -> snapshot.api.takeIf { snapshot.optionsAvailable } }
                .distinctUntilChanged()
                .collectLatest { api ->
                    if (api != null) {
                        apiPreferences.value = api.preferences
                        apiPreferences.drop(1).debounce(0.5.seconds).collect {
                            api.preferences = it
                        }
                    }
                }
        }
    }

    /** Empties every folder's `.stversions`. */
    fun clearVersions() {
        val api = snapshot.value.api ?: return
        appScope.launch {
            if (withContext(ioDispatcher) { clearStVersions(api.folders) }) {
                toast(R.string.clear_stversions_done)
            }
        }
    }

    fun undoIgnoredDevicesAndFolders() {
        val api = snapshot.value.api ?: return
        appScope.launch {
            withContext(ioDispatcher) {
                api.undoIgnoredDevicesAndFolders()
                api.sendConfig()
            }
            toast(R.string.undo_ignored_devices_folders_done)
        }
    }

    /** Saves Syncthing's support bundle to Downloads. */
    fun downloadSupportBundle() {
        val api = snapshot.value.api ?: return
        supportBundleState.value = SupportBundle(SupportBundleDownloadState.DOWNLOADING)
        appScope.launch(ioDispatcher) {
            val deviceName = api.localDevice.displayName
            val downloadDir = FileUtils.getExternalStorageDownloadsDirectory()
            val targetFileName = "$downloadDir/syncthing-support-bundle_$deviceName.zip"
            supportBundleState.value =
                SupportBundle(SupportBundleDownloadState.DOWNLOADING, targetFileName)
            api.downloadSupportBundle(File(targetFileName)) { success ->
                supportBundleState.value =
                    SupportBundle(
                        if (success) SupportBundleDownloadState.SUCCESS
                        else SupportBundleDownloadState.FAILED,
                        targetFileName,
                    )
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
    }
}

private var RestApi.preferences: Preferences
    get() {
        if (!this.isConfigLoaded) return MapPreferences()

        val values = mutableMapOf<String, Any>()
        values[SyncthingOptionKeys.DEVICE_NAME] = this.localDevice.name
        values[SyncthingOptionKeys.API_KEY] = this.apiKey
        values[SyncthingOptionKeys.USAGE_REPORTING] = this.isUsageReportingAccepted

        this.options.let { options ->
            values[SyncthingOptionKeys.LISTEN_ADDRESSES] =
                checkNotNull(options.listenAddresses).joinToString()
            values[SyncthingOptionKeys.INCOMING_RATE_LIMIT] = options.maxRecvKbps
            values[SyncthingOptionKeys.OUTGOING_RATE_LIMIT] = options.maxSendKbps
            values[SyncthingOptionKeys.NAT_TRAVERSAL] = options.natEnabled
            values[SyncthingOptionKeys.LOCAL_DISCOVERY] = options.localAnnounceEnabled
            values[SyncthingOptionKeys.GLOBAL_DISCOVERY] = options.globalAnnounceEnabled
            values[SyncthingOptionKeys.RELAYING] = options.relaysEnabled
            values[SyncthingOptionKeys.GLOBAL_SERVERS] =
                checkNotNull(options.globalAnnounceServers).joinToString()
            values[SyncthingOptionKeys.CRASH_REPORTING] = options.crashReportingEnabled
        }

        this.gui.let { gui ->
            values[SyncthingOptionKeys.WEB_GUI_PORT] =
                gui.bindPort.toIntOrNull() ?: Constants.DEFAULT_WEBGUI_TCP_PORT
            values[SyncthingOptionKeys.WEB_GUI_REMOTE_ACCESS] = gui.bindAddress != BIND_LOCALHOST
            gui.user?.let { values[SyncthingOptionKeys.WEB_GUI_USERNAME] = it }
            // use password saved in shared preference only
            // values[SyncthingOptionKeys.WEB_GUI_PASSWORD] = gui.password
        }

        return MapPreferences(values)
    }
    set(value) {
        val splitter = Splitter.on(",").trimResults().omitEmptyStrings()
        val valueMap = value.asMap()

        // update calls that change internal state of RestApi
        // Only when the switch changed: it shows "off" also while the question is unanswered.
        (valueMap[SyncthingOptionKeys.USAGE_REPORTING] as Boolean?)
            ?.takeIf { it != this.isUsageReportingAccepted }
            ?.let { this.setUsageReporting(it) }

        // assignments to options and gui
        val options = this.options
        val gui = this.gui
        for ((key, mapValue) in valueMap) {
            when (key) {
                SyncthingOptionKeys.LISTEN_ADDRESSES -> {
                    val addresses = splitter.splitToList(mapValue as String)
                    options.listenAddresses = addresses.toTypedArray()
                }
                SyncthingOptionKeys.INCOMING_RATE_LIMIT -> {
                    options.maxRecvKbps = mapValue as Int
                }
                SyncthingOptionKeys.OUTGOING_RATE_LIMIT -> {
                    options.maxSendKbps = mapValue as Int
                }
                SyncthingOptionKeys.NAT_TRAVERSAL -> {
                    options.natEnabled = mapValue as Boolean
                }
                SyncthingOptionKeys.LOCAL_DISCOVERY -> {
                    options.localAnnounceEnabled = mapValue as Boolean
                }
                SyncthingOptionKeys.GLOBAL_DISCOVERY -> {
                    options.globalAnnounceEnabled = mapValue as Boolean
                }
                SyncthingOptionKeys.RELAYING -> {
                    options.relaysEnabled = mapValue as Boolean
                }
                SyncthingOptionKeys.GLOBAL_SERVERS -> {
                    val servers = splitter.splitToList(mapValue as String)
                    options.globalAnnounceServers = servers.toTypedArray()
                }
                SyncthingOptionKeys.CRASH_REPORTING -> {
                    options.crashReportingEnabled = mapValue as Boolean
                }
                SyncthingOptionKeys.WEB_GUI_REMOTE_ACCESS -> {
                    val address = if (mapValue as Boolean) BIND_ALL else BIND_LOCALHOST
                    val port =
                        (valueMap[SyncthingOptionKeys.WEB_GUI_PORT] as Int?)
                            ?: Constants.DEFAULT_WEBGUI_TCP_PORT
                    gui.address = "$address:$port"
                }
                SyncthingOptionKeys.WEB_GUI_PORT -> {
                    /* Ignore here, handled in WEB_GUI_REMOTE_ACCESS */
                }
                SyncthingOptionKeys.WEB_GUI_USERNAME -> {
                    gui.user = mapValue as String
                }
                SyncthingOptionKeys.WEB_GUI_PASSWORD -> {
                    val hashed = BCrypt.hashpw(mapValue as String, BCrypt.gensalt(4))
                    gui.password = hashed
                }
            }
        }
        // does not call sendConfig
        this.editSettings(gui, options)

        val localDevice = this.localDevice
        val deviceName = valueMap[SyncthingOptionKeys.DEVICE_NAME] as String? ?: localDevice.name
        localDevice.name = deviceName
        // this calls the sendConfig method
        this.updateDevice(localDevice)
    }

private fun clearStVersions(folders: List<Folder>): Boolean {
    for (folder in folders) {
        val dir = File(folder.path + "/" + Constants.FOLDER_NAME_STVERSIONS)
        if (dir.exists() && dir.isDirectory) {
            Log.d(TAG, "Delete dir: $dir")
            deleteContents(dir)
        }
    }
    return true
}

private fun deleteContents(dir: File) {
    val files = dir.listFiles()
    files?.let {
        for (file in it) {
            if (file.isDirectory) {
                deleteContents(file)
            }
            file.delete()
        }
    }
}
