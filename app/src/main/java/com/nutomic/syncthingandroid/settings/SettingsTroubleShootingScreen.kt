package com.nutomic.syncthingandroid.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsTroubleshootingEntry(navigator: Navigator<SettingsRoute>) {
    entry<SettingsRoute.Troubleshooting> {
        SettingsTroubleshootingScreen(navigator = navigator)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTroubleshootingScreen(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val issueTrackerUrl = stringResource(R.string.fork_issue_tracker_url)

    val stTraceOptions by rememberPreferenceState(
        Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
        getFallbackDebugFacilities(),
    )
    val stTraceSelection = rememberPreferenceState(Constants.PREF_DEBUG_FACILITIES_ENABLED, setOf<String>())
    val envVars = rememberPreferenceState(Constants.PREF_ENVIRONMENT_VARIABLES, "")

    val selectedValues = stTraceSelection.value.intersect(stTraceOptions)

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_debug),
    ) {
        item {
            val items: List<@Composable (Shape) -> Unit> = listOf(
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.report_issue_title),
                        summary = stringResource(R.string.fork_open_issue_tracker_summary, issueTrackerUrl),
                        shape = shape,
                        onClick = { uriHandler.openUri(issueTrackerUrl) },
                    )
                },
                { shape -> VerboseLogItem(shape = shape) },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.open_log),
                        summary = stringResource(R.string.open_log_summary),
                        shape = shape,
                        onClick = {
                            val intent = Intent(context, LogActivity::class.java)
                            context.startActivity(intent)
                        },
                    )
                },
                { shape ->
                    SettingsMultiSelectListItem(
                        title = stringResource(R.string.sttrace_title),
                        value = selectedValues,
                        values = stTraceOptions.sorted(),
                        valueToText = { it },
                        emptyText = stringResource(R.string.sttrace_none_selected),
                        shape = shape,
                        onValueChange = { stTraceSelection.value = it },
                    )
                },
                { shape ->
                    SettingsTextFieldItem(
                        title = stringResource(R.string.environment_variables),
                        value = envVars.value,
                        shape = shape,
                        onValueChange = { envVars.value = it },
                        textToValue = { validateEnvVars(it, context) },
                    )
                },
                { shape -> ResetDatabaseItem(shape = shape) },
                { shape -> ResetDeltasItem(shape = shape) },
            )
            SettingsGroup(
                items = items,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun VerboseLogItem(shape: Shape) {
    val activity = LocalActivity.current
    val verboseLog = rememberPreferenceState(Constants.PREF_VERBOSE_LOG, false)

    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsSwitch(
        title = stringResource(R.string.verbose_log_title),
        summary = stringResource(R.string.verbose_log_summary),
        checked = verboseLog.value,
        shape = shape,
        onCheckedChange = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            title = { Text(stringResource(R.string.dialog_settings_restart_app_title)) },
            text = { Text(stringResource(R.string.dialog_settings_restart_app_question)) },
            onDismissRequest = { showAlert = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        verboseLog.value = !verboseLog.value
                        showAlert = false
                        // restart whole app
                        restartApp(activity)
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun restartApp(activity: Activity?) {
    if (activity == null || activity.isFinishing) {
        return
    }

    val stopServiceIntent = Intent(activity, SyncthingService::class.java)
    activity.stopService(stopServiceIntent)

    val context = activity.applicationContext
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val mainIntent = Intent.makeRestartActivityTask(intent?.component)
    activity.finishAndRemoveTask()
    context.startActivity(mainIntent)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun ResetDatabaseItem(shape: Shape) {
    val context = LocalContext.current

    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = stringResource(R.string.st_reset_database_title),
        shape = shape,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            title = { Text(stringResource(R.string.st_reset_database_title)) },
            text = { Text(stringResource(R.string.st_reset_database_question)) },
            onDismissRequest = { showAlert = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlert = false
                        val intent = Intent(context, SyncthingService::class.java).apply {
                            action = SyncthingService.ACTION_RESET_DATABASE
                        }
                        context.startService(intent)
                        Toast.makeText(context, R.string.st_reset_database_done, Toast.LENGTH_LONG).show()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ResetDeltasItem(shape: Shape) {
    val context = LocalContext.current

    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = stringResource(R.string.st_reset_deltas_title),
        shape = shape,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            title = { Text(stringResource(R.string.st_reset_deltas_title)) },
            text = { Text(stringResource(R.string.st_reset_deltas_question)) },
            onDismissRequest = { showAlert = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlert = false
                        val intent = Intent(context, SyncthingService::class.java).apply {
                            action = SyncthingService.ACTION_RESET_DELTAS
                        }
                        context.startService(intent)
                        Toast.makeText(context, R.string.st_reset_deltas_done, Toast.LENGTH_LONG).show()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun validateEnvVars(input: String, context: Context): String? {
    if (input.isEmpty()) {
        return input
    }

    val isValid = input.split(" ").all { pair ->
        pair.split("=", limit = 2).size == 2
    }

    if (isValid) {
        return input
    }

    // Found an invalid "VAR=VALUE" pair.
    Toast.makeText(context, R.string.toast_invalid_environment_variables, Toast.LENGTH_LONG).show()
    return null
}

// Syncthing v0.14.47 debug facilities.
private fun getFallbackDebugFacilities(): Set<String> = setOf(
    "beacon",
    "config",
    "connections",
    "db",
    "dialer",
    "discover",
    "events",
    "fs",
    "http",
    "main",
    "model",
    "nat",
    "pmp",
    "protocol",
    "scanner",
    "sha256",
    "stats",
    "sync",
    "upgrade",
    "upnp",
    "versioner",
    "walkfs",
    "watchaggregator",
)
