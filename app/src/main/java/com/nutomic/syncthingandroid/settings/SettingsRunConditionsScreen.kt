package com.nutomic.syncthingandroid.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.ui.graphics.Shape
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsRunConditionsEntry(
    navigator: Navigator<SettingsRoute>,
) {
    entry<SettingsRoute.RunConditions> {
        SettingsRunConditionsRoute(navigator = navigator)
    }
}


@Composable
fun SettingsRunConditionsRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: RunConditionsViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val stService = viewModel.snapshot.collectAsStateWithLifecycle().value.service

    DisposableEffect(lifecycleOwner, stService, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.evaluatePending()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            viewModel.evaluatePending()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    SettingsRunConditionsScreen(
        navigator = navigator,
        markPending = viewModel::markPending,
        modifier = modifier,
    )
}

@Composable
fun SettingsRunConditionsScreen(
    navigator: Navigator<SettingsRoute>,
    markPending: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val runOnWifi = rememberPreferenceState(Constants.PREF_RUN_ON_WIFI, true)
    val runOnMeteredWifi = rememberPreferenceState(Constants.PREF_RUN_ON_METERED_WIFI, false)
    val runOnSpecifiedSsid = rememberPreferenceState(Constants.PREF_USE_WIFI_SSID_WHITELIST, false)

    val runOnMobileData = rememberPreferenceState(Constants.PREF_RUN_ON_MOBILE_DATA, false)
    val runOnRoaming = rememberPreferenceState(Constants.PREF_RUN_ON_ROAMING, false)

    val powerSourceNames = stringArrayResource(R.array.power_source_entries)
    val powerSourceValues = stringArrayResource(R.array.power_source_values)
    val powerSource = rememberPreferenceState(Constants.PREF_POWER_SOURCE, powerSourceValues[0])

    val respectBatterySaving = rememberPreferenceState(Constants.PREF_RESPECT_BATTERY_SAVING, true)
    val respectMasterSync = rememberPreferenceState(Constants.PREF_RESPECT_MASTER_SYNC, false)
    val flightMode = rememberPreferenceState(Constants.PREF_RUN_IN_FLIGHT_MODE, false)

    val runScheduled = rememberPreferenceState(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)
    val syncDuration = rememberPreferenceState(Constants.PREF_SYNC_DURATION_MINUTES, "5")
    val sleepInterval = rememberPreferenceState(Constants.PREF_SLEEP_INTERVAL_MINUTES, "60")


    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.run_conditions_title),
        description = stringResource(R.string.run_conditions_summary),
    ) {
        groupedSection(R.string.category_wifi, wifiItems(runOnWifi, runOnMeteredWifi, runOnSpecifiedSsid, markPending))
        groupedSection(R.string.category_mobile_data, mobileDataItems(runOnMobileData, runOnRoaming, markPending))
        groupedSection(
            R.string.category_battery,
            batteryItems(
                powerSourceNames,
                powerSourceValues,
                powerSource,
                respectBatterySaving,
                respectMasterSync,
                flightMode,
                markPending,
            ),
        )
        groupedSection(
            R.string.category_schedule,
            scheduleItems(context, runScheduled, syncDuration, sleepInterval, markPending),
        )
    }
}

private fun wifiItems(
    runOnWifi: MutableState<Boolean>,
    runOnMeteredWifi: MutableState<Boolean>,
    runOnSpecifiedSsid: MutableState<Boolean>,
    markPending: () -> Unit,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_on_wifi_title),
            summary = stringResource(R.string.run_on_wifi_summary),
            checked = runOnWifi.value,
            shape = shape,
            onCheckedChange = {
                runOnWifi.value = it
                markPending()
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_on_metered_wifi_title),
            summary = stringResource(R.string.run_on_metered_wifi_summary),
            checked = runOnMeteredWifi.value,
            shape = shape,
            enabled = runOnWifi.value,
            onCheckedChange = {
                runOnMeteredWifi.value = it
                markPending()
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_on_whitelisted_wifi_title),
            summary = stringResource(R.string.run_on_whitelisted_wifi_summary),
            checked = runOnSpecifiedSsid.value,
            shape = shape,
            enabled = runOnWifi.value,
            onCheckedChange = {
                runOnSpecifiedSsid.value = it
                markPending()
            },
        )
    },
    { shape ->
        WifiSsidItem(
            enabled = runOnWifi.value && runOnSpecifiedSsid.value,
            setPendingEvaluation = markPending,
            shape = shape,
        )
    },
)

private fun mobileDataItems(
    runOnMobileData: MutableState<Boolean>,
    runOnRoaming: MutableState<Boolean>,
    markPending: () -> Unit,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_on_mobile_data_title),
            summary = stringResource(R.string.run_on_mobile_data_summary),
            checked = runOnMobileData.value,
            shape = shape,
            onCheckedChange = {
                runOnMobileData.value = it
                markPending()
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_on_roaming_title),
            summary = stringResource(R.string.run_on_roaming_summary),
            checked = runOnRoaming.value,
            shape = shape,
            enabled = runOnMobileData.value,
            onCheckedChange = {
                runOnRoaming.value = it
                markPending()
            },
        )
    },
)

private fun batteryItems(
    powerSourceNames: Array<String>,
    powerSourceValues: Array<String>,
    powerSource: MutableState<String>,
    respectBatterySaving: MutableState<Boolean>,
    respectMasterSync: MutableState<Boolean>,
    flightMode: MutableState<Boolean>,
    markPending: () -> Unit,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsListItem(
            title = stringResource(R.string.power_source_title),
            value = powerSource.value,
            values = powerSourceValues.toList(),
            valueToText = { powerSourceNames[powerSourceValues.indexOf(it)] },
            shape = shape,
            onValueChange = {
                powerSource.value = it
                markPending()
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.respect_battery_saving_title),
            summary = stringResource(R.string.respect_battery_saving_summary),
            checked = respectBatterySaving.value,
            shape = shape,
            onCheckedChange = {
                respectBatterySaving.value = it
                markPending()
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.respect_master_sync_title),
            summary = stringResource(R.string.respect_master_sync_summary),
            checked = respectMasterSync.value,
            shape = shape,
            onCheckedChange = {
                respectMasterSync.value = it
                markPending()
            },
        )
    },
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_in_flight_mode_title),
            summary = stringResource(R.string.run_in_flight_mode_summary),
            checked = flightMode.value,
            shape = shape,
            onCheckedChange = {
                flightMode.value = it
                markPending()
            },
        )
    },
)

private fun scheduleItems(
    context: Context,
    runScheduled: MutableState<Boolean>,
    syncDuration: MutableState<String>,
    sleepInterval: MutableState<String>,
    markPending: () -> Unit,
): List<@Composable (Shape) -> Unit> = listOf(
    { shape ->
        SettingsSwitch(
            title = stringResource(R.string.run_on_time_schedule_title),
            summary = stringResource(R.string.run_on_time_schedule_summary),
            checked = runScheduled.value,
            shape = shape,
            onCheckedChange = {
                runScheduled.value = it
                markPending()
            },
        )
    },
    { shape ->
        val syncDurationError = stringResource(R.string.invalid_integer_value, 1, 1440/* 24h */)
        SettingsTextFieldItem(
            title = stringResource(R.string.sync_duration_minutes_title),
            summary = stringResource(R.string.sync_duration_minutes_summary, syncDuration.value),
            value = syncDuration.value,
            shape = shape,
            onValueChange = {
                syncDuration.value = it
                markPending()
            },
            textToValue = { text ->
                val mins = text.toIntOrNull()
                if (mins == null || mins !in 1..1440) {
                    Toast.makeText(context, syncDurationError, Toast.LENGTH_LONG).show()
                    null
                } else {
                    text
                }
            },
        )
    },
    { shape ->
        val sleepIntervalError = stringResource(R.string.invalid_integer_value, 1, 30240/* 3w */)
        SettingsTextFieldItem(
            title = stringResource(R.string.sleep_interval_minutes_title),
            summary = stringResource(R.string.sync_duration_minutes_summary, sleepInterval.value),
            value = sleepInterval.value,
            shape = shape,
            onValueChange = {
                sleepInterval.value = it
                markPending()
            },
            textToValue = { text ->
                val mins = text.toIntOrNull()
                if (mins == null || mins !in 1..30240) {
                    Toast.makeText(context, sleepIntervalError, Toast.LENGTH_LONG).show()
                    null
                } else {
                    text
                }
            },
        )
    },
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun WifiSsidItem(
    enabled: Boolean,
    setPendingEvaluation: () -> Unit,
    shape: Shape,
) {
    val context = LocalContext.current
    val locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var specifiedSsids by rememberPreferenceState(Constants.PREF_WIFI_SSID_WHITELIST, setOf<String>())
    var knownSsids by rememberPreferenceState(Constants.PREF_KNOWN_WIFI_SSIDS, setOf<String>())

    var showSelectionAlert by rememberSaveable { mutableStateOf(false) }

    val permissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                R.string.sync_only_wifi_ssids_location_permission_rejected_dialog_content,
                Toast.LENGTH_LONG
            ).show()
        } else {
            val intent = Intent(context, SyncthingService::class.java)
                .setAction(SyncthingService.ACTION_REFRESH_NETWORK_INFO)
            context.startService(intent)
        }
    }

    val isPermissionGranted = permissionState.status == PermissionStatus.Granted
    val isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)

    SideEffect(isPermissionGranted) {
        val currentSsid = getCurrentWifiSsid(context)
        var updatedKnown = knownSsids
        var updatedSelection = specifiedSsids

        if (!currentSsid.isNullOrBlank()) {
            updatedKnown = knownSsids + currentSsid
        }
        val toRemoveSelected = specifiedSsids - updatedKnown
        if (toRemoveSelected.isNotEmpty()) {
            updatedSelection = specifiedSsids - toRemoveSelected
        }

        knownSsids = updatedKnown
        specifiedSsids = updatedSelection
    }

    val specifiedSsidSummary = if (specifiedSsids.isNotEmpty())
        stringResource(R.string.run_on_whitelisted_wifi_networks, specifiedSsids.joinToString())
    else
        stringResource(R.string.wifi_ssid_whitelist_empty)

    SettingsItem(
        title = stringResource(R.string.specify_wifi_ssid_whitelist),
        summary = specifiedSsidSummary,
        shape = shape,
        enabled = enabled,
        onClick = {
            if (isPermissionGranted && isLocationEnabled) {
                showSelectionAlert = true
            } else if (!isPermissionGranted) {
                permissionState.launchPermissionRequest()
            } else if (knownSsids.isEmpty()) {
                Toast.makeText(
                    context,
                    R.string.sync_only_wifi_ssids_wifi_turn_on_wifi,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    R.string.sync_only_wifi_ssids_wifi_turn_on_location,
                    Toast.LENGTH_LONG
                ).show()
            }
        },
    )
    if (showSelectionAlert) {
        SsidSelectionDialog(
            knownSsids = knownSsids,
            selectedSsids = specifiedSsids,
            onConfirm = { selection ->
                specifiedSsids = selection
                showSelectionAlert = false
                setPendingEvaluation()
            },
            onDismiss = { showSelectionAlert = false },
        )
    }
}

@Composable
private fun SsidSelectionDialog(
    knownSsids: Set<String>,
    selectedSsids: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var dialogValue by rememberSaveable { mutableStateOf(selectedSsids) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.specify_wifi_ssid_whitelist)) },
        text = {
            val lazyListState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = lazyListState,
            ) {
                items(knownSsids.toList()) { itemValue ->
                    val checked = itemValue in dialogValue
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(checked, true, Role.Checkbox) {
                                    dialogValue =
                                        if (it) dialogValue + itemValue else dialogValue - itemValue
                                }
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(24.dp))
                        Text(
                            text = AnnotatedString(
                                itemValue.replace("^\"".toRegex(), "")
                                    .replace("\"$".toRegex(), "")
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dialogValue) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@Suppress("DEPRECATION")
private fun getCurrentWifiSsid(context: Context): String? {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ssid = wifiManager.connectionInfo?.ssid

    return if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID)
        null
    else
        ssid
}
