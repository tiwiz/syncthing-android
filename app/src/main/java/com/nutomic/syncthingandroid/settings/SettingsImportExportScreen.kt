package com.nutomic.syncthingandroid.settings

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.service.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.LocalPreferenceFlow
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsImportExportEntry(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    activityScope: CoroutineScope,
) {
    entry<SettingsRoute.ImportExport> {
        SettingsImportExportScreen(
            navigator = navigator,
            serviceState = serviceState,
            activityScope = activityScope,
        )
    }
}


@Composable
fun SettingsImportExportScreen(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    activityScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val defaultBackupPath = "backups/syncthing/config.zip"
    val backupPath = rememberPreferenceState(Constants.PREF_BACKUP_REL_PATH_TO_ZIP,defaultBackupPath)

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_backup),
    ) {
        groupedSection(
            R.string.preference_category_explanation,
            listOf(
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.backup_password_summary),
                        shape = shape,
                        onClick = {},
                    )
                },
            ),
        )
        groupedSection(
            R.string.preference_category_prepare,
            listOf(
                { shape ->
                    SettingsTextFieldItem(
                        title = stringResource(R.string.backup_rel_path_to_zip),
                        value = backupPath.value,
                        summary = backupPath.value.ifBlank { defaultBackupPath },
                        shape = shape,
                        onValueChange = { backupPath.value = it },
                        textToValue = { it.ifBlank { defaultBackupPath } },
                    )
                },
                { shape -> PasswordItem(shape = shape) },
            ),
        )
        groupedSection(
            R.string.preference_category_actions,
            listOf(
                { shape ->
                    ExportConfigItem(
                        navigator = navigator,
                        serviceState = serviceState,
                        shape = shape,
                        scope = activityScope,
                    )
                },
                { shape ->
                    ImportConfigItem(
                        navigator = navigator,
                        serviceState = serviceState,
                        shape = shape,
                        scope = activityScope,
                    )
                },
            ),
        )
    }
}

@Composable
private fun PasswordItem(shape: Shape) {
    val password = rememberPreferenceState(Constants.PREF_BACKUP_PASSWORD, "")
    val isPasswordSet = password.value.isNotBlank()

    val passwordSummary = if (!isPasswordSet)
        stringResource(R.string.backup_password_not_set)
    else
        stringResource(R.string.backup_password_set_masked)

    SettingsPasswordItem(
        title = stringResource(R.string.backup_password_title),
        summary = passwordSummary,
        value = password.value,
        shape = shape,
        onValueChange = { password.value = it },
        modifier = Modifier.semantics {
            password()
            isSensitiveData = true
        },
    )
}

@Composable
private fun ExportConfigItem(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    shape: Shape,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val stService = serviceState.service

    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = stringResource(R.string.export_config),
        shape = shape,
        enabled = stService != null,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.export_config)) },
            text = { Text(stringResource(R.string.dialog_confirm_export)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stService?.let { service ->
                            scope.launch(ioDispatcher) {
                                showAlert = false
                                service.exportConfig().also { success ->
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                R.string.config_export_successful_no_path,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            navigator.navigateUp()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                R.string.config_export_failed,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val intent = Intent(context, LogActivity::class.java)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
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
private fun ImportConfigItem(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    shape: Shape,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val stService = serviceState.service
    val prefs = LocalPreferenceFlow.current

    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = stringResource(R.string.import_config),
        shape = shape,
        enabled = stService != null,
        onClick = { showAlert = true },
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.import_config)) },
            text = { Text(stringResource(R.string.dialog_confirm_import)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stService?.let { service ->
                            scope.launch(ioDispatcher) {
                                showAlert = false
                                service.importConfig().also { success ->
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                R.string.config_imported_successful,
                                                Toast.LENGTH_LONG
                                            ).show()

                                            // apply theme from restored config
                                            val raw: Any? = prefs.value[Constants.PREF_APP_THEME]
                                            val theme = when (raw) {
                                                is Int -> raw
                                                is String -> raw.toIntOrNull()
                                                else -> null
                                            } ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                            AppCompatDelegate.setDefaultNightMode(theme)

                                            service.evaluateRunConditions()

                                            navigator.navigateUp()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                R.string.config_import_failed_no_path,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val intent = Intent(context, LogActivity::class.java)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
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
