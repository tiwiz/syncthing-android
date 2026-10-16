package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nutomic.syncthingandroid.R

/**
 * The top bar's overflow menu, with what used to be in the navigation drawer. Recent changes, the
 * web GUI and restart need Syncthing [running]; the web GUI is left out on TVs ([showWebGui]). With
 * the app [runningAsService], exit asks first. [open] is hoisted so the menu key can toggle it.
 */
@Composable
internal fun MainMenu(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    running: Boolean,
    showWebGui: Boolean,
    runningAsService: Boolean,
    actions: MenuActions,
    modifier: Modifier = Modifier,
) {
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val close = { onOpenChange(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { onOpenChange(!open) }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = close,
            modifier = Modifier.testTag("mainMenu"),
        ) {
            MenuEntry(Icons.Outlined.QrCode2, R.string.show_device_id) {
                close()
                actions.onShowDeviceId()
            }
            MenuEntry(Icons.Outlined.Restore, R.string.recent_changes_title, enabled = running) {
                close()
                actions.onRecentChanges()
            }
            if (showWebGui) {
                MenuEntry(
                    Icons.AutoMirrored.Outlined.ViewQuilt,
                    R.string.web_gui_title,
                    enabled = running,
                ) {
                    close()
                    actions.onWebGui()
                }
            }
            MenuEntry(Icons.Outlined.ImportExport, R.string.category_backup) {
                close()
                actions.onBackup()
            }
            MenuEntry(Icons.Outlined.Autorenew, R.string.restart, enabled = running) {
                close()
                confirmRestart = true
            }
            HorizontalDivider()
            MenuEntry(Icons.AutoMirrored.Outlined.ExitToApp, R.string.exit) {
                close()
                if (runningAsService) confirmExit = true else actions.onExit()
            }
        }
    }
    if (confirmRestart) {
        ConfirmRestartDialog(onDismiss = { confirmRestart = false }) {
            confirmRestart = false
            actions.onRestart()
        }
    }
    if (confirmExit) {
        ConfirmExitDialog(onDismiss = { confirmExit = false }, onConfirm = actions::onExit)
    }
}

@Composable
private fun MenuEntry(
    icon: ImageVector,
    label: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun ConfirmRestartDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_confirm_restart)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ConfirmExitDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_exit_while_running_as_service_title)) },
        text = { Text(stringResource(R.string.dialog_exit_while_running_as_service_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.no)) } },
    )
}
