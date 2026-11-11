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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.AppAlertDialog

/**
 * What used to be in the navigation drawer, for the overflow menu or the expanded rail ([content]).
 * Recent changes, the web GUI and restart need Syncthing [running]; the web GUI is left out on TVs
 * ([showWebGui]). With the app [runningAsService], exit asks first. [onChoose] runs before an
 * entry's action, so a menu can close. Restart and exit only raise their question in
 * [confirmations]; the screen owns and shows it ([MenuConfirmationDialog]): the entries move
 * between the top bar, the rail and a spatial panel as the window changes, and an open question
 * must not go with the one that showed it.
 */
@Composable
internal fun MenuEntries(
    running: Boolean,
    showWebGui: Boolean,
    runningAsService: Boolean,
    actions: MenuActions,
    confirmations: MenuConfirmations,
    onChoose: () -> Unit,
    content: @Composable (List<MenuEntryItem>) -> Unit,
) {
    fun entry(
        icon: ImageVector,
        label: Int,
        enabled: Boolean = true,
        apart: Boolean = false,
        action: () -> Unit,
    ) =
        MenuEntryItem(icon, label, enabled, apart) {
            onChoose()
            action()
        }
    val entries = buildList {
        add(entry(Icons.Outlined.QrCode2, R.string.show_device_id) { actions.onShowDeviceId() })
        add(
            entry(Icons.Outlined.Restore, R.string.recent_changes_title, enabled = running) {
                actions.onRecentChanges()
            }
        )
        if (showWebGui) {
            add(
                entry(Icons.AutoMirrored.Outlined.ViewQuilt, R.string.web_gui_title, running) {
                    actions.onWebGui()
                }
            )
        }
        add(entry(Icons.Outlined.ImportExport, R.string.category_backup) { actions.onBackup() })
        add(
            entry(Icons.Outlined.Autorenew, R.string.restart, running) {
                confirmations.question = MenuQuestion.RESTART
            }
        )
        add(
            entry(Icons.AutoMirrored.Outlined.ExitToApp, R.string.exit, apart = true) {
                if (runningAsService) confirmations.question = MenuQuestion.EXIT
                else actions.onExit()
            }
        )
    }
    content(entries)
}

/**
 * The question [confirmations] is asking, if any. Shown by the screen with its other dialogs, so
 * that only one is up at a time.
 */
@Composable
internal fun MenuConfirmationDialog(confirmations: MenuConfirmations, actions: MenuActions) {
    val close = { confirmations.question = null }
    when (confirmations.question) {
        MenuQuestion.RESTART ->
            ConfirmRestartDialog(onDismiss = close) {
                close()
                actions.onRestart()
            }
        MenuQuestion.EXIT ->
            ConfirmExitDialog(onDismiss = close) {
                close()
                actions.onExit()
            }
        null -> Unit
    }
}

/** What the menu asks before it acts. */
internal enum class MenuQuestion {
    RESTART,
    EXIT,
}

/** The question the menu is asking, if any: a new one takes the place of the one before. */
@Stable
internal class MenuConfirmations(question: MenuQuestion? = null) {
    var question by mutableStateOf(question)

    companion object {
        val Saver: Saver<MenuConfirmations, String> =
            Saver(
                save = { it.question?.name.orEmpty() },
                restore = { saved ->
                    MenuConfirmations(MenuQuestion.entries.firstOrNull { it.name == saved })
                },
            )
    }
}

/** [MenuConfirmations] that survive a recreation. */
@Composable
internal fun rememberMenuConfirmations(): MenuConfirmations =
    rememberSaveable(saver = MenuConfirmations.Saver) { MenuConfirmations() }

/** The top bar's overflow menu. [open] is hoisted so the menu key can toggle it. */
@Composable
internal fun MainMenu(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    running: Boolean,
    showWebGui: Boolean,
    runningAsService: Boolean,
    actions: MenuActions,
    confirmations: MenuConfirmations,
    modifier: Modifier = Modifier,
) {
    MenuEntries(
        running,
        showWebGui,
        runningAsService,
        actions,
        confirmations,
        onChoose = { onOpenChange(false) },
    ) { entries ->
        Box(modifier = modifier) {
            IconButton(onClick = { onOpenChange(!open) }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options))
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { onOpenChange(false) },
                modifier = Modifier.testTag("mainMenu"),
            ) {
                for (entry in entries) {
                    if (entry.apart) HorizontalDivider()
                    MenuEntry(entry.icon, entry.label, entry.enabled, entry.onClick)
                }
            }
        }
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
    AppAlertDialog(
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
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_exit_while_running_as_service_title)) },
        text = { Text(stringResource(R.string.dialog_exit_while_running_as_service_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.no)) } },
    )
}
