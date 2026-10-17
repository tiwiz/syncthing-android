package com.nutomic.syncthingandroid.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsImportExportEntry(
    navigator: Navigator<SettingsRoute>,
) {
    entry<SettingsRoute.ImportExport> {
        SettingsImportExportRoute(navigator = navigator)
    }
}


@Composable
fun SettingsImportExportRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    SettingsEffects(viewModel.events, navigator)
    SettingsImportExportScreen(
        navigator = navigator,
        serviceBound = snapshot.service != null,
        onExport = viewModel::exportConfig,
        onImport = viewModel::importConfig,
        modifier = modifier,
    )
}


@Composable
fun SettingsImportExportScreen(
    navigator: Navigator<SettingsRoute>,
    serviceBound: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
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
                    ConfirmedActionItem(
                        title = stringResource(R.string.export_config),
                        question = stringResource(R.string.dialog_confirm_export),
                        enabled = serviceBound,
                        shape = shape,
                        onConfirm = onExport,
                    )
                },
                { shape ->
                    ConfirmedActionItem(
                        title = stringResource(R.string.import_config),
                        question = stringResource(R.string.dialog_confirm_import),
                        enabled = serviceBound,
                        shape = shape,
                        onConfirm = onImport,
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

/** A row that asks [question] before running [onConfirm]. */
@Composable
private fun ConfirmedActionItem(
    title: String,
    question: String,
    enabled: Boolean,
    shape: Shape,
    onConfirm: () -> Unit,
) {
    var showAlert by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        title = title,
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
