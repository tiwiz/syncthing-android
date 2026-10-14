package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.Compression

/** A device found by local discovery, with its addresses. */
@Immutable internal data class DiscoveredEntry(val deviceId: String, val addresses: String)

/** What the device screen shows besides the form. */
@Immutable
internal data class DeviceScreenInfo(
    val createMode: Boolean,
    val expertMode: Boolean,
    val canScanQr: Boolean,
    val currentAddress: String?,
    val syncthingVersion: String?,
    /** Null until queried, or once an id was entered. */
    val discovered: List<DiscoveredEntry>?,
    val localDiscoveryEnabled: Boolean,
)

/** Which dialog the device screen shows. */
internal enum class DeviceDialog {
    NONE,
    DELETE,
    DISCARD,
    COMPRESSION,
}

/** The actions of the device screen. */
internal interface DeviceScreenActions {
    fun onFormChange(form: DeviceForm)

    fun onSave()

    fun onDelete()

    fun onBack()

    fun onScanQr()

    fun onShowDeviceId()

    fun onRefreshDiscovered()

    fun onAddFolder()

    fun onOpenSyncConditions()

    fun onDialog(dialog: DeviceDialog)

    fun onConfirmDialog(dialog: DeviceDialog)
}

/** The device's id, name, addresses, folder shares and options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceEditScreen(
    form: DeviceForm,
    info: DeviceScreenInfo,
    dialog: DeviceDialog,
    actions: DeviceScreenActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (info.createMode) R.string.add_device else R.string.edit_device
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = actions::onSave) {
                        Icon(
                            Icons.Filled.Done,
                            stringResource(
                                if (info.createMode) R.string.create else R.string.save_title
                            ),
                        )
                    }
                    if (!info.createMode) {
                        IconButton(onClick = { actions.onDialog(DeviceDialog.DELETE) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete_device))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeviceIdSection(form, info, actions)
            Fields(form, actions)
            FoldersSection(form, actions)
            OptionsSection(form, info, actions)
            ConnectionSection(info)
        }
    }
    DeviceDialogs(form, dialog, actions)
}

@Composable
private fun DeviceIdSection(
    form: DeviceForm,
    info: DeviceScreenInfo,
    actions: DeviceScreenActions,
) {
    if (info.createMode) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = form.deviceId,
                onValueChange = { actions.onFormChange(form.copy(deviceId = it, changed = true)) },
                label = { Text(stringResource(R.string.device_id)) },
                trailingIcon = {
                    if (info.canScanQr) {
                        IconButton(onClick = actions::onScanQr) {
                            Icon(
                                Icons.Filled.QrCode2,
                                stringResource(R.string.scan_qr_code_description),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("deviceId"),
            )
            info.discovered?.let {
                DiscoveredDevices(it, info.localDiscoveryEnabled, form, actions)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(form.deviceId, style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = actions::onShowDeviceId) {
                Icon(Icons.Filled.Share, stringResource(R.string.share_device_id))
            }
        }
    }
}

@Composable
private fun DiscoveredDevices(
    discovered: List<DiscoveredEntry>,
    localDiscoveryEnabled: Boolean,
    form: DeviceForm,
    actions: DeviceScreenActions,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = actions::onRefreshDiscovered),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.discovered_devices_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.Refresh, contentDescription = null)
        }
        if (discovered.isEmpty()) {
            Text(
                text =
                    if (localDiscoveryEnabled) {
                        stringResource(
                            R.string.discovered_device_list_empty,
                            stringResource(R.string.url_syncthing_homepage),
                        )
                    } else {
                        stringResource(R.string.local_discovery_disabled)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        for (entry in discovered) {
            val caption =
                entry.deviceId + if (entry.addresses.isEmpty()) "" else " (${entry.addresses})"
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                            actions.onFormChange(
                                form.copy(deviceId = entry.deviceId, changed = true)
                            )
                        }
                        .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Fields(form: DeviceForm, actions: DeviceScreenActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { actions.onFormChange(form.copy(name = it, changed = true)) },
            label = { Text(stringResource(R.string.name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("name"),
        )
        OutlinedTextField(
            value = form.addresses,
            onValueChange = { actions.onFormChange(form.copy(addresses = it, changed = true)) },
            label = { Text(stringResource(R.string.addresses)) },
            modifier = Modifier.fillMaxWidth().testTag("addresses"),
        )
    }
}

@Composable
private fun FoldersSection(form: DeviceForm, actions: DeviceScreenActions) {
    Column {
        SectionTitle(R.string.folders)
        if (form.shares.isEmpty()) {
            Text(
                text = stringResource(R.string.folders_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = actions::onAddFolder)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        for (share in form.shares) {
            fun update(changed: FolderShare) =
                actions.onFormChange(
                    form.copy(
                        shares =
                            form.shares.map { if (it.folderId == share.folderId) changed else it },
                        changed = true,
                    )
                )
            SwitchRow(
                title = share.label,
                checked = share.shared,
                onCheckedChange = { update(share.copy(shared = it)) },
            )
            if (share.shared) {
                OutlinedTextField(
                    value = share.encryptionPassword,
                    onValueChange = { update(share.copy(encryptionPassword = it)) },
                    label = { Text(stringResource(R.string.deviceEncryptionPasswordHint)) },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("password:${share.label}"),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OptionsSection(form: DeviceForm, info: DeviceScreenInfo, actions: DeviceScreenActions) {
    Column {
        if (info.expertMode) {
            val titles = stringArrayResource(R.array.compress_entries)
            val values = stringArrayResource(R.array.compress_values)
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { actions.onDialog(DeviceDialog.COMPRESSION) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    stringResource(R.string.compression),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    titles[compressionIndex(values, form.compression)],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwitchRow(stringResource(R.string.introducer), form.introducer) {
            actions.onFormChange(form.copy(introducer = it, changed = true))
        }
        SwitchRow(stringResource(R.string.autoAcceptFolders), form.autoAcceptFolders) {
            actions.onFormChange(form.copy(autoAcceptFolders = it, changed = true))
        }
        SwitchRow(stringResource(R.string.pause_device), form.paused) {
            actions.onFormChange(form.copy(paused = it, changed = true))
        }
        SwitchRow(
            stringResource(R.string.untrusted_device),
            form.untrusted,
            summary = stringResource(R.string.untrusted_device_explanation),
        ) {
            actions.onFormChange(form.copy(untrusted = it, changed = true))
        }
        if (!info.createMode) {
            SwitchRow(
                stringResource(R.string.custom_sync_conditions_title),
                form.customSyncConditions,
                summary = stringResource(R.string.custom_sync_conditions_description),
            ) {
                actions.onFormChange(form.copy(customSyncConditions = it, changed = true))
            }
            Text(
                text = stringResource(R.string.custom_sync_conditions_dialog),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (form.customSyncConditions) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(
                            enabled = form.customSyncConditions,
                            onClick = actions::onOpenSyncConditions,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ConnectionSection(info: DeviceScreenInfo) {
    Column {
        info.currentAddress?.let { LabelledValue(R.string.current_address, it) }
        info.syncthingVersion?.let { LabelledValue(R.string.syncthing_version_title, it) }
    }
}

@Composable
private fun LabelledValue(label: Int, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceDialogs(form: DeviceForm, dialog: DeviceDialog, actions: DeviceScreenActions) {
    val dismiss = { actions.onDialog(DeviceDialog.NONE) }
    when (dialog) {
        DeviceDialog.NONE -> Unit
        DeviceDialog.DELETE ->
            ConfirmDialog(
                R.string.remove_device_confirm,
                android.R.string.yes,
                android.R.string.no,
                dismiss,
            ) {
                actions.onConfirmDialog(DeviceDialog.DELETE)
            }
        DeviceDialog.DISCARD ->
            ConfirmDialog(
                R.string.dialog_discard_changes,
                android.R.string.ok,
                android.R.string.cancel,
                dismiss,
            ) {
                actions.onConfirmDialog(DeviceDialog.DISCARD)
            }
        DeviceDialog.COMPRESSION -> {
            val titles = stringArrayResource(R.array.compress_entries)
            val values = stringArrayResource(R.array.compress_values)
            val selected = compressionIndex(values, form.compression)
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.compression)) },
                text = {
                    Column {
                        values.zip(titles).forEachIndexed { index, (value, title) ->
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                        selected = index == selected,
                                        role = Role.RadioButton,
                                    ) {
                                        // Only a different compression is a change.
                                        if (index != selected) {
                                            actions.onFormChange(
                                                form.copy(compression = value, changed = true)
                                            )
                                        }
                                        dismiss()
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RadioButton(selected = index == selected, onClick = null)
                                Text(title)
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }
    }
}

/** The index of [value] among the compression [values]; Syncthing's default for unknown ones. */
private fun compressionIndex(values: Array<String>, value: String): Int =
    Compression.fromIndex(values.lastIndexOf(value)).index

private const val DISABLED_ALPHA = 0.38f
