package com.nutomic.syncthingandroid.settings

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.navigation3.runtime.EntryProviderScope
import com.google.common.base.Splitter
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.MapPreferences
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceFlow
import me.zhanghai.compose.preference.rememberPreferenceState
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

private const val TAG = "SettingsSyncthingOptionsScreen"

fun EntryProviderScope<SettingsRoute>.settingsSyncthingOptionsEntry(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    activityScope: CoroutineScope,
) {
    entry<SettingsRoute.SyncthingOptions> {
        SettingsSyncthingOptionsScreen(
            navigator = navigator,
            serviceState = serviceState,
            activityScope = activityScope,
        )
    }
}

private object Keys {
    const val DEVICE_NAME = "device_name"
    const val API_KEY = "api_key"
    const val USAGE_REPORTING = "usage_reporting"

    const val LISTEN_ADDRESSES = "listen_addresses"
    const val INCOMING_RATE_LIMIT = "incoming_rate_limit"
    const val OUTGOING_RATE_LIMIT = "outgoing_rate_limit"
    const val NAT_TRAVERSAL = "nat_traversal"
    const val LOCAL_DISCOVERY = "local_discovery"
    const val GLOBAL_DISCOVERY = "global_discovery"
    const val RELAYING = "relaying"
    const val GLOBAL_SERVERS = "global_servers"

    const val WEB_GUI_PORT = "web_gui_port"
    const val WEB_GUI_REMOTE_ACCESS = "web_gui_remote_access"
    const val WEB_GUI_USERNAME = "web_gui_username"
    const val WEB_GUI_PASSWORD = Constants.PREF_WEBUI_PASSWORD

    const val CRASH_REPORTING = "crash_reporting"
}

private const val BIND_ALL = "0.0.0.0"
private const val BIND_LOCALHOST = "127.0.0.1"


@OptIn(FlowPreview::class)
@Composable
fun SettingsSyncthingOptionsScreen(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    activityScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val stService = serviceState.service
    val stServiceTick = serviceState.updateTick

    val sharedPrefWebGuiPassword = rememberPreferenceState(Constants.PREF_WEBUI_PASSWORD, "")

    val isStServiceActive by remember(stService, stServiceTick) { derivedStateOf {
        stService?.currentState == SyncthingService.State.ACTIVE && stService.api?.isConfigLoaded == true
    } }
    val apiPrefFlow: MutableStateFlow<Preferences> = remember {
        MutableStateFlow(stService?.api?.preferences ?: MapPreferences())
    }

    LaunchedEffect(stService?.api, isStServiceActive) {
        val currentApi = stService?.api
        if (currentApi != null && isStServiceActive) {
            apiPrefFlow.value = currentApi.preferences
            apiPrefFlow
                .drop(1)
                .debounce(0.5.seconds)
                .collect { currentApi.preferences = it }
        }
    }


    ProvidePreferenceFlow(apiPrefFlow) {
        val deviceName = rememberPreferenceState(Keys.DEVICE_NAME, "")
        val apiKey = rememberPreferenceState(Keys.API_KEY, "")
        val usageReporting = rememberPreferenceState(Keys.USAGE_REPORTING, false)

        val listenAddresses = rememberPreferenceState(Keys.LISTEN_ADDRESSES, "default")
        val incomingRateLimit = rememberPreferenceState(Keys.INCOMING_RATE_LIMIT, 0)
        val outgoingRateLimit = rememberPreferenceState(Keys.OUTGOING_RATE_LIMIT, 0)
        val natTraversal = rememberPreferenceState(Keys.NAT_TRAVERSAL, false)
        val localDiscovery = rememberPreferenceState(Keys.LOCAL_DISCOVERY, false)
        val globalDiscovery = rememberPreferenceState(Keys.GLOBAL_DISCOVERY, false)
        val relaying = rememberPreferenceState(Keys.RELAYING, false)
        val globalServers = rememberPreferenceState(Keys.GLOBAL_SERVERS, "default")

        val webGuiPort = rememberPreferenceState(Keys.WEB_GUI_PORT, Constants.DEFAULT_WEBGUI_TCP_PORT)
        val webGuiRemoteAccess = rememberPreferenceState(Keys.WEB_GUI_REMOTE_ACCESS, false)
        val webGuiUsername = rememberPreferenceState(Keys.WEB_GUI_USERNAME, "syncthing")
        val webGuiPassword = rememberPreferenceState(Keys.WEB_GUI_PASSWORD, sharedPrefWebGuiPassword.value)

        val crashReporting = rememberPreferenceState(Keys.CRASH_REPORTING, true)


        SettingsScaffold(
            onNavigateBack = { navigator.navigateBack() },
            modifier = modifier,
            title = stringResource(R.string.category_syncthing_options),
            description = stringResource(R.string.category_syncthing_options_summary),
        ) {
            groupedSection(
                R.string.category_general,
                generalItems(
                    deviceName,
                    apiKey,
                    usageReporting,
                    isStServiceActive,
                    context,
                    clipboard,
                    activityScope,
                ),
            )
            groupedSection(
                R.string.category_connections,
                connectionItems(
                    listenAddresses,
                    incomingRateLimit,
                    outgoingRateLimit,
                    natTraversal,
                    localDiscovery,
                    globalDiscovery,
                    relaying,
                    globalServers,
                    isStServiceActive,
                    context,
                ),
            )
            groupedSection(
                R.string.web_gui_title,
                webGuiItems(
                    webGuiPort,
                    webGuiRemoteAccess,
                    webGuiUsername,
                    webGuiPassword,
                    sharedPrefWebGuiPassword,
                    isStServiceActive,
                    context,
                ),
            )
            groupedSection(
                R.string.category_advanced,
                advancedItems(crashReporting, stService, isStServiceActive, navigator, activityScope),
            )
        }
    }
}

private fun generalItems(
    deviceName: MutableState<String>,
    apiKey: MutableState<String>,
    usageReporting: MutableState<Boolean>,
    isStServiceActive: Boolean,
    context: Context,
    clipboard: Clipboard,
    scope: CoroutineScope,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsTextFieldItem(
            title = stringResource(R.string.device_name),
            value = deviceName.value,
            shape = shape,
            onValueChange = { deviceName.value = it },
            textToValue = { it },
            enabled = isStServiceActive,
        )
    },
    { shape ->
        val apiKeyTitle = stringResource(R.string.syncthing_api_key)
        SettingsItem(
            title = apiKeyTitle,
            summary = apiKey.value,
            shape = shape,
            enabled = isStServiceActive,
            modifier = Modifier.semantics(true) {
                isSensitiveData = true
                password()
            },
            onClick = {
                val clipData = ClipData.newPlainText(apiKeyTitle, apiKey.value).toClipEntry()
                scope.launch {
                    clipboard.setClipEntry(clipData)
                    // Android 13+ shows a system confirmation automatically
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(
                            context,
                            R.string.api_key_copied_to_clipboard,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.usage_reporting),
            checked = usageReporting.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { usageReporting.value = it },
        )
    },
)

private fun connectionItems(
    listenAddresses: MutableState<String>,
    incomingRateLimit: MutableState<Int>,
    outgoingRateLimit: MutableState<Int>,
    natTraversal: MutableState<Boolean>,
    localDiscovery: MutableState<Boolean>,
    globalDiscovery: MutableState<Boolean>,
    relaying: MutableState<Boolean>,
    globalServers: MutableState<String>,
    isStServiceActive: Boolean,
    context: Context,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsTextFieldItem(
            title = stringResource(R.string.listen_address),
            value = listenAddresses.value,
            shape = shape,
            onValueChange = { listenAddresses.value = it },
            textToValue = { it },
            enabled = isStServiceActive,
        )
    },
    { shape ->
        val rateLimitError = stringResource(R.string.invalid_integer_value, 0, Int.MAX_VALUE)
        SettingsTextFieldItem(
            title = stringResource(R.string.max_recv_kbps),
            value = incomingRateLimit.value.toString(),
            shape = shape,
            onValueChange = { incomingRateLimit.value = it.toIntOrNull() ?: 0 },
            textToValue = {
                val newVal = it.toIntOrNull()
                if (newVal == null) {
                    Toast.makeText(context, rateLimitError, Toast.LENGTH_LONG).show()
                    null
                } else {
                    newVal.toString()
                }
            },
            enabled = isStServiceActive,
        )
    },
    { shape ->
        val rateLimitError = stringResource(R.string.invalid_integer_value, 0, Int.MAX_VALUE)
        SettingsTextFieldItem(
            title = stringResource(R.string.max_send_kbps),
            value = outgoingRateLimit.value.toString(),
            shape = shape,
            onValueChange = { outgoingRateLimit.value = it.toIntOrNull() ?: 0 },
            textToValue = {
                val newVal = it.toIntOrNull()
                if (newVal == null) {
                    Toast.makeText(context, rateLimitError, Toast.LENGTH_LONG).show()
                    null
                } else {
                    newVal.toString()
                }
            },
            enabled = isStServiceActive,
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.enable_nat_traversal),
            checked = natTraversal.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { natTraversal.value = it },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.local_announce_enabled),
            checked = localDiscovery.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { localDiscovery.value = it },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.global_announce_enabled),
            checked = globalDiscovery.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { globalDiscovery.value = it },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.enable_relaying),
            checked = relaying.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { relaying.value = it },
        )
    },
    { shape ->
        SettingsTextFieldItem(
            title = stringResource(R.string.global_announce_server),
            value = globalServers.value,
            shape = shape,
            onValueChange = { globalServers.value = it },
            textToValue = { it },
            enabled = isStServiceActive,
        )
    },
)

private fun webGuiItems(
    webGuiPort: MutableState<Int>,
    webGuiRemoteAccess: MutableState<Boolean>,
    webGuiUsername: MutableState<String>,
    webGuiPassword: MutableState<String>,
    sharedPrefWebGuiPassword: MutableState<String>,
    isStServiceActive: Boolean,
    context: Context,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        val portError = stringResource(R.string.invalid_port_number, 1024, 65535)
        SettingsTextFieldItem(
            title = stringResource(R.string.webui_tcp_port_title),
            value = webGuiPort.value.toString(),
            shape = shape,
            onValueChange = { webGuiPort.value = it.toIntOrNull() ?: Constants.DEFAULT_WEBGUI_TCP_PORT },
            textToValue = {
                val newValue = it.toIntOrNull()
                if (newValue == null || newValue !in 1024..65535) {
                    Toast.makeText(context, portError, Toast.LENGTH_LONG).show()
                    null
                } else {
                    newValue.toString()
                }
            },
            enabled = isStServiceActive,
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.webui_remote_access_title),
            summary = stringResource(R.string.webui_remote_access_summary),
            checked = webGuiRemoteAccess.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { webGuiRemoteAccess.value = it },
        )
    },
    { shape ->
        SettingsTextFieldItem(
            title = stringResource(R.string.webui_username_title),
            value = webGuiUsername.value,
            shape = shape,
            onValueChange = { webGuiUsername.value = it },
            textToValue = { it },
            enabled = isStServiceActive,
        )
    },
    { shape ->
        SettingsPasswordItem(
            title = stringResource(R.string.webui_password_title),
            value = webGuiPassword.value,
            shape = shape,
            onValueChange = {
                webGuiPassword.value = it
                sharedPrefWebGuiPassword.value = it
            },
            enabled = isStServiceActive,
        )
    },
)

private fun advancedItems(
    crashReporting: MutableState<Boolean>,
    stService: SyncthingService?,
    isStServiceActive: Boolean,
    navigator: Navigator<SettingsRoute>,
    scope: CoroutineScope,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.crash_reporting),
            checked = crashReporting.value,
            shape = shape,
            enabled = isStServiceActive,
            onCheckedChange = { crashReporting.value = it },
        )
    },
    { shape ->
        SettingsItem(
            title = stringResource(R.string.webui_custom_cert_title),
            summary = stringResource(R.string.webui_custom_cert_summary),
            shape = shape,
            enabled = stService != null,
            onClick = { navigator.navigateTo(SettingsRoute.CustomCertificate) },
        )
    },
    { shape ->
        ClearStVersionItem(
            stService = stService,
            enabled = isStServiceActive,
            shape = shape,
            scope = scope,
        )
    },
    { shape ->
        SupportBundleItem(
            stService = stService,
            enabled = isStServiceActive,
            shape = shape,
            scope = scope,
        )
    },
    { shape ->
        UndoIgnoredDevicesFoldersItem(
            stService = stService,
            enabled = isStServiceActive,
            shape = shape,
            scope = scope,
        )
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearStVersionItem(
    stService: SyncthingService?,
    enabled: Boolean,
    shape: Shape,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = stringResource(R.string.clear_stversions_title),
        summary = stringResource(R.string.clear_stversions_summary),
        shape = shape,
        enabled = enabled,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.clear_stversions_title)) },
            text = { Text(stringResource(R.string.clear_stversions_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stService?.api?.let { api ->
                            scope.launch(ioDispatcher) {
                                val folders = api.folders
                                if (clearStVersions(folders)) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            R.string.clear_stversions_done,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                showAlert = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UndoIgnoredDevicesFoldersItem(
    stService: SyncthingService?,
    enabled: Boolean,
    shape: Shape,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = stringResource(R.string.undo_ignored_devices_folders_title),
        shape = shape,
        enabled = enabled,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.undo_ignored_devices_folders_title)) },
            text = { Text(stringResource(R.string.undo_ignored_devices_folders_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stService?.api?.let { api ->
                            scope.launch(ioDispatcher) {
                                api.undoIgnoredDevicesAndFolders()
                                api.sendConfig()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        R.string.undo_ignored_devices_folders_done,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                showAlert = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}


@Composable
private fun SupportBundleItem(
    stService: SyncthingService?,
    enabled: Boolean,
    shape: Shape,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var supportBundleState by remember { mutableStateOf(SupportBundleDownloadState.INIT) }
    var supportBundleFileName by remember { mutableStateOf("") }

    val summary = when (supportBundleState) {
        SupportBundleDownloadState.INIT -> null
        SupportBundleDownloadState.DOWNLOADING -> stringResource(R.string.download_support_bundle_in_progress)
        SupportBundleDownloadState.SUCCESS -> stringResource(
            R.string.download_support_bundle_succeeded,
            supportBundleFileName,
        )
        SupportBundleDownloadState.FAILED -> stringResource(R.string.download_support_bundle_failed)
    }

    SettingsItem(
        title = stringResource(R.string.download_support_bundle_title),
        summary = summary,
        shape = shape,
        enabled = enabled && supportBundleState != SupportBundleDownloadState.DOWNLOADING,
        onClick = {
            stService?.api?.let { api ->
                supportBundleState = SupportBundleDownloadState.DOWNLOADING
                scope.launch(ioDispatcher) {
                    val deviceName = api.localDevice.displayName
                    val downloadDir = FileUtils.getExternalStorageDownloadsDirectory()
                    val targetFileName = "$downloadDir/syncthing-support-bundle_$deviceName.zip"
                    supportBundleFileName = targetFileName
                    val targetFile = File(targetFileName)
                    api.downloadSupportBundle(targetFile) { success ->
                        supportBundleState = if (success)
                            SupportBundleDownloadState.SUCCESS
                        else
                            SupportBundleDownloadState.FAILED
                    }
                }
            }
        },
    )
}

private enum class SupportBundleDownloadState {
    INIT, DOWNLOADING, SUCCESS, FAILED
}

private var RestApi.preferences: Preferences
    get() {
        if (!this.isConfigLoaded) return MapPreferences()

        val values = mutableMapOf<String, Any>()
        values[Keys.DEVICE_NAME] = this.localDevice.name
        values[Keys.API_KEY] = this.apiKey
        values[Keys.USAGE_REPORTING] = this.isUsageReportingAccepted

        this.options.let { options ->
            values[Keys.LISTEN_ADDRESSES] = checkNotNull(options.listenAddresses).joinToString()
            values[Keys.INCOMING_RATE_LIMIT] = options.maxRecvKbps
            values[Keys.OUTGOING_RATE_LIMIT] = options.maxSendKbps
            values[Keys.NAT_TRAVERSAL] = options.natEnabled
            values[Keys.LOCAL_DISCOVERY] = options.localAnnounceEnabled
            values[Keys.GLOBAL_DISCOVERY] = options.globalAnnounceEnabled
            values[Keys.RELAYING] = options.relaysEnabled
            values[Keys.GLOBAL_SERVERS] = checkNotNull(options.globalAnnounceServers).joinToString()
            values[Keys.CRASH_REPORTING] = options.crashReportingEnabled
        }

        this.gui.let { gui ->
            values[Keys.WEB_GUI_PORT] = gui.bindPort.toIntOrNull() ?: Constants.DEFAULT_WEBGUI_TCP_PORT
            values[Keys.WEB_GUI_REMOTE_ACCESS] = gui.bindAddress != BIND_LOCALHOST
            gui.user?.let { values[Keys.WEB_GUI_USERNAME] = it }
            // use password saved in shared preference only
            // values[Keys.WEB_GUI_PASSWORD] = gui.password
        }

        return MapPreferences(values)
    }
    set(value) {
        val splitter = Splitter.on(",").trimResults().omitEmptyStrings()
        val valueMap = value.asMap()

        // update calls that change internal state of RestApi
        valueMap[Keys.USAGE_REPORTING]?.let { this.setUsageReporting(it as Boolean) }

        // assignments to options and gui
        val options = this.options
        val gui = this.gui
        for ((key, mapValue) in valueMap) {
            when (key) {
                Keys.LISTEN_ADDRESSES -> {
                    val addresses = splitter.splitToList(mapValue as String)
                    options.listenAddresses = addresses.toTypedArray()
                }
                Keys.INCOMING_RATE_LIMIT -> {
                    options.maxRecvKbps = mapValue as Int
                }
                Keys.OUTGOING_RATE_LIMIT -> {
                    options.maxSendKbps = mapValue as Int
                }
                Keys.NAT_TRAVERSAL -> {
                    options.natEnabled = mapValue as Boolean
                }
                Keys.LOCAL_DISCOVERY -> {
                    options.localAnnounceEnabled = mapValue as Boolean
                }
                Keys.GLOBAL_DISCOVERY -> {
                    options.globalAnnounceEnabled = mapValue as Boolean
                }
                Keys.RELAYING -> {
                    options.relaysEnabled = mapValue as Boolean
                }
                Keys.GLOBAL_SERVERS -> {
                    val servers = splitter.splitToList(mapValue as String)
                    options.globalAnnounceServers = servers.toTypedArray()
                }
                Keys.CRASH_REPORTING -> {
                    options.crashReportingEnabled = mapValue as Boolean
                }
                Keys.WEB_GUI_REMOTE_ACCESS -> {
                    val address = if (mapValue as Boolean) BIND_ALL else BIND_LOCALHOST
                    val port = (valueMap[Keys.WEB_GUI_PORT] as Int?) ?: Constants.DEFAULT_WEBGUI_TCP_PORT
                    gui.address = "$address:$port"
                }
                Keys.WEB_GUI_PORT -> {/* Ignore here, handled in WEB_GUI_REMOTE_ACCESS */}
                Keys.WEB_GUI_USERNAME -> {
                    gui.user = mapValue as String
                }
                Keys.WEB_GUI_PASSWORD -> {
                    val hashed = BCrypt.hashpw(mapValue as String, BCrypt.gensalt(4))
                    gui.password = hashed
                }
            }
        }
        // does not call sendConfig
        this.editSettings(gui, options)

        val localDevice = this.localDevice
        val deviceName = valueMap[Keys.DEVICE_NAME] as String? ?: localDevice.name
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
