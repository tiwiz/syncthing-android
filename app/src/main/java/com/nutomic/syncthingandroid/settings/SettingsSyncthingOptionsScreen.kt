package com.nutomic.syncthingandroid.settings

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceFlow
import me.zhanghai.compose.preference.rememberPreferenceState

fun EntryProviderScope<SettingsRoute>.settingsSyncthingOptionsEntry(
    navigator: Navigator<SettingsRoute>,
) {
    entry<SettingsRoute.SyncthingOptions> {
        SettingsSyncthingOptionsRoute(navigator = navigator)
    }
}

/** The preference keys of Syncthing's own options, as [SyncthingOptionsViewModel] maps them. */
internal object SyncthingOptionKeys {
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

private typealias Keys = SyncthingOptionKeys

/** The maintenance actions of the advanced section. */
@Stable
class SyncthingMaintenance(
    val supportBundle: SupportBundle,
    val onClearVersions: () -> Unit,
    val onDownloadSupportBundle: () -> Unit,
    val onUndoIgnored: () -> Unit,
)

@Composable
fun SettingsSyncthingOptionsRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: SyncthingOptionsViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val supportBundle by viewModel.supportBundle.collectAsStateWithLifecycle()
    val maintenance =
        remember(supportBundle, viewModel) {
            SyncthingMaintenance(
                supportBundle = supportBundle,
                onClearVersions = viewModel::clearVersions,
                onDownloadSupportBundle = viewModel::downloadSupportBundle,
                onUndoIgnored = viewModel::undoIgnoredDevicesAndFolders,
            )
        }
    SettingsSyncthingOptionsScreen(
        navigator = navigator,
        apiPreferences = viewModel.apiPreferences,
        serviceBound = snapshot.service != null,
        isStServiceActive = snapshot.optionsAvailable,
        maintenance = maintenance,
        modifier = modifier,
    )
}

// The preference library edits the options through the MutableStateFlow it is given.
@Suppress("MutableParams")
@Composable
fun SettingsSyncthingOptionsScreen(
    navigator: Navigator<SettingsRoute>,
    apiPreferences: MutableStateFlow<Preferences>,
    serviceBound: Boolean,
    isStServiceActive: Boolean,
    maintenance: SyncthingMaintenance,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val sharedPrefWebGuiPassword = rememberPreferenceState(Constants.PREF_WEBUI_PASSWORD, "")

    ProvidePreferenceFlow(apiPreferences) {
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
                    scope,
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
                advancedItems(crashReporting, serviceBound, isStServiceActive, navigator, maintenance),
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
    serviceBound: Boolean,
    isStServiceActive: Boolean,
    navigator: Navigator<SettingsRoute>,
    maintenance: SyncthingMaintenance,
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
            enabled = serviceBound,
            onClick = { navigator.navigateTo(SettingsRoute.CustomCertificate) },
        )
    },
    { shape ->
        ConfirmedItem(
            title = stringResource(R.string.clear_stversions_title),
            summary = stringResource(R.string.clear_stversions_summary),
            question = stringResource(R.string.clear_stversions_question),
            enabled = isStServiceActive,
            shape = shape,
            onConfirm = maintenance.onClearVersions,
        )
    },
    { shape ->
        SupportBundleItem(
            supportBundle = maintenance.supportBundle,
            enabled = isStServiceActive,
            shape = shape,
            onClick = maintenance.onDownloadSupportBundle,
        )
    },
    { shape ->
        ConfirmedItem(
            title = stringResource(R.string.undo_ignored_devices_folders_title),
            summary = null,
            question = stringResource(R.string.undo_ignored_devices_folders_question),
            enabled = isStServiceActive,
            shape = shape,
            onConfirm = maintenance.onUndoIgnored,
        )
    },
)

/** A row that asks [question] before running [onConfirm]. */
@Composable
private fun ConfirmedItem(
    title: String,
    summary: String?,
    question: String,
    enabled: Boolean,
    shape: Shape,
    onConfirm: () -> Unit,
) {
    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = title,
        summary = summary,
        shape = shape,
        enabled = enabled,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(title) },
            text = { Text(question) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlert = false
                        onConfirm()
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
    supportBundle: SupportBundle,
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val summary = when (supportBundle.state) {
        SupportBundleDownloadState.INIT -> null
        SupportBundleDownloadState.DOWNLOADING -> stringResource(R.string.download_support_bundle_in_progress)
        SupportBundleDownloadState.SUCCESS -> stringResource(
            R.string.download_support_bundle_succeeded,
            supportBundle.fileName,
        )
        SupportBundleDownloadState.FAILED -> stringResource(R.string.download_support_bundle_failed)
    }

    SettingsItem(
        title = stringResource(R.string.download_support_bundle_title),
        summary = summary,
        shape = shape,
        enabled = enabled && supportBundle.state != SupportBundleDownloadState.DOWNLOADING,
        onClick = onClick,
    )
}
