package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R

/**
 * The main screen's navigation drawer. Recent changes, the web GUI and restart need Syncthing
 * [running]; the web GUI is left out on TVs ([showWebGui]). With the app [runningAsService], exit
 * asks first. When [requestFocus] is set (a TV remote opened the drawer), the first entry takes the
 * focus.
 */
@Composable
internal fun MainDrawer(
    running: Boolean,
    showWebGui: Boolean,
    runningAsService: Boolean,
    requestFocus: Boolean,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(requestFocus) { if (requestFocus) firstItemFocusRequester.requestFocus() }

    ModalDrawerSheet(modifier = modifier.testTag("drawer")) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            DrawerHeader()
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            LazyColumn(modifier = Modifier.padding(vertical = 8.dp).weight(1f)) {
                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.QrCode2, null) },
                        label = { Text(stringResource(R.string.show_device_id)) },
                        onClick = actions::onShowDeviceId,
                        modifier = Modifier.focusRequester(firstItemFocusRequester),
                    )
                }
                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.Restore, null) },
                        label = { Text(stringResource(R.string.recent_changes_title)) },
                        onClick = actions::onRecentChanges,
                        enabled = running,
                    )
                }
                if (showWebGui) {
                    item {
                        DrawerItem(
                            icon = { Icon(Icons.AutoMirrored.Outlined.ViewQuilt, null) },
                            label = { Text(stringResource(R.string.web_gui_title)) },
                            onClick = actions::onWebGui,
                            enabled = running,
                        )
                    }
                }
                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.ImportExport, null) },
                        label = { Text(stringResource(R.string.category_backup)) },
                        onClick = actions::onBackup,
                    )
                }
                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.Autorenew, null) },
                        label = { Text(stringResource(R.string.restart)) },
                        onClick = { confirmRestart = true },
                        enabled = running,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    label = { Text(stringResource(R.string.settings_title)) },
                    onClick = actions::onSettings,
                )
                DrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Outlined.ExitToApp, null) },
                    label = { Text(stringResource(R.string.exit)) },
                    onClick = { if (runningAsService) confirmExit = true else actions.onExit() },
                )
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

/** A drawer entry; NavigationDrawerItem has no disabled state. */
@Composable
private fun DrawerItem(
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (enabled) {
        NavigationDrawerItem(
            icon = icon,
            label = label,
            onClick = onClick,
            selected = false,
            modifier = modifier,
        )
    } else {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        Surface(
            color = Color.Transparent,
            modifier = modifier.heightIn(min = 56.dp).fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 24.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides color, content = icon)
                Spacer(Modifier.width(12.dp))
                CompositionLocalProvider(LocalContentColor provides color, content = label)
            }
        }
    }
}

@Composable
private fun DrawerHeader() {
    Surface(color = Color.Transparent, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 24.dp, bottom = 16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_monochrome_ui),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(32.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
