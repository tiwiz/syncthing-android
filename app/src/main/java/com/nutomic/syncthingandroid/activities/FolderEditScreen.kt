package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import java.util.concurrent.TimeUnit

/** What the folder screen shows besides the form. */
@Immutable
internal data class FolderScreenInfo(
    val createMode: Boolean,
    val expertMode: Boolean,
    /** Null until a path is known. */
    val canWriteToPath: Boolean?,
    val saving: Boolean,
)

/** Which dialog the folder screen shows. */
internal enum class FolderDialog {
    NONE,
    DELETE,
    DISCARD,
}

/** The actions of the folder screen. */
internal interface FolderScreenActions {
    fun onFormChange(form: FolderForm)

    fun onSave()

    fun onBack()

    fun onPickPath()

    fun onPickAdvancedDirectory()

    fun onFolderType()

    fun onPullOrder()

    fun onVersioning()

    fun onOpenSyncConditions()

    fun onAddDevice()

    fun onDialog(dialog: FolderDialog)

    fun onConfirmDialog(dialog: FolderDialog)
}

/** The folder's label, id, path, devices and options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderEditScreen(
    form: FolderForm,
    info: FolderScreenInfo,
    dialog: FolderDialog,
    actions: FolderScreenActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (info.createMode) R.string.create_folder else R.string.edit_folder
                        ),
                        modifier = Modifier.testTag("editTitle"),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = actions::onSave, enabled = !info.saving) {
                        Icon(
                            Icons.Filled.Done,
                            stringResource(
                                if (info.createMode) R.string.create else R.string.save_title
                            ),
                        )
                    }
                    if (!info.createMode) {
                        IconButton(
                            onClick = { actions.onDialog(FolderDialog.DELETE) },
                            enabled = !info.saving,
                        ) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete_folder))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NameAndPath(form, info, actions)
                DevicesSection(form, actions)
                SettingsSection(form, info, actions)
                SwitchesSection(form, info, actions)
                IgnoreListSection(form, info, actions)
            }
            if (info.saving) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = SAVING_ALPHA),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.state_saving))
                    }
                }
            }
        }
    }
    when (dialog) {
        FolderDialog.NONE -> Unit
        FolderDialog.DELETE ->
            ConfirmDialog(
                R.string.remove_folder_confirm,
                android.R.string.yes,
                android.R.string.no,
                { actions.onDialog(FolderDialog.NONE) },
            ) {
                actions.onConfirmDialog(FolderDialog.DELETE)
            }
        FolderDialog.DISCARD ->
            ConfirmDialog(
                R.string.dialog_discard_changes,
                android.R.string.ok,
                android.R.string.cancel,
                { actions.onDialog(FolderDialog.NONE) },
            ) {
                actions.onConfirmDialog(FolderDialog.DISCARD)
            }
    }
}

@Composable
private fun NameAndPath(form: FolderForm, info: FolderScreenInfo, actions: FolderScreenActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = form.label,
            onValueChange = { actions.onFormChange(form.copy(label = it, changed = true)) },
            label = { Text(stringResource(R.string.folder_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("label"),
        )
        OutlinedTextField(
            value = form.id,
            onValueChange = { actions.onFormChange(form.copy(id = it, changed = true)) },
            label = { Text(stringResource(R.string.folder_id)) },
            singleLine = true,
            enabled = info.createMode,
            modifier = Modifier.fillMaxWidth().testTag("id"),
        )
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(enabled = info.createMode, onClick = actions::onPickPath)
                    .padding(vertical = 8.dp)
                    .testTag("path"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = form.path.ifEmpty { stringResource(R.string.directory) },
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (form.path.isEmpty() || !info.createMode)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (info.createMode) {
            OutlinedButton(onClick = actions::onPickAdvancedDirectory) {
                Text(stringResource(R.string.advanced_directory_selection))
            }
        }
    }
}

@Composable
private fun DevicesSection(form: FolderForm, actions: FolderScreenActions) {
    Column {
        SectionTitle(R.string.devices)
        if (form.shares.isEmpty()) {
            Text(
                text = stringResource(R.string.devices_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = actions::onAddDevice)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        for (share in form.shares) {
            fun update(changed: DeviceShare) =
                actions.onFormChange(
                    form.copy(
                        shares =
                            form.shares.map { if (it.deviceId == share.deviceId) changed else it },
                        changed = true,
                    )
                )
            SwitchRow(title = share.name, checked = share.shared) {
                update(share.copy(shared = it))
            }
            if (share.shared) {
                OutlinedTextField(
                    value = share.encryptionPassword,
                    onValueChange = { update(share.copy(encryptionPassword = it)) },
                    label = { Text(stringResource(R.string.deviceEncryptionPasswordHint)) },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("password:${share.name}"),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SettingsSection(
    form: FolderForm,
    info: FolderScreenInfo,
    actions: FolderScreenActions,
) {
    Column {
        val (typeTitle, typeDescription, typeIcon) = folderTypeTexts(form.type)
        SettingRow(
            title = stringResource(R.string.folder_type),
            value = stringResource(typeTitle),
            description = stringResource(typeDescription),
            icon = typeIcon,
            enabled = form.type != Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED,
            tag = "folderType",
            onClick = actions::onFolderType,
        )
        info.canWriteToPath?.let {
            Text(
                text =
                    stringResource(
                        if (it) R.string.folder_path_readwrite else R.string.folder_path_readonly
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (info.expertMode && form.type != Constants.FOLDER_TYPE_SEND_ONLY) {
            val (orderTitle, orderDescription) = pullOrderTexts(form.order)
            SettingRow(
                title = stringResource(R.string.pull_order),
                value = stringResource(orderTitle),
                description = stringResource(orderDescription),
                tag = "pullOrder",
                onClick = actions::onPullOrder,
            )
        }
        SettingRow(
            title = stringResource(R.string.file_versioning),
            value = versioningTitle(form),
            description = versioningDescription(form),
            tag = "versioning",
            onClick = actions::onVersioning,
        )
    }
}

@Composable
private fun SwitchesSection(
    form: FolderForm,
    info: FolderScreenInfo,
    actions: FolderScreenActions,
) {
    Column {
        SwitchRow(
            stringResource(R.string.folder_fileWatcher),
            form.fsWatcherEnabled,
            summary = stringResource(R.string.folder_fileWatcherDescription),
        ) {
            actions.onFormChange(form.copy(fsWatcherEnabled = it, changed = true))
        }
        SwitchRow(stringResource(R.string.folder_pause), form.paused) {
            actions.onFormChange(form.copy(paused = it, changed = true))
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("syncConditions"),
            )
        }
        if (info.expertMode) {
            SwitchRow(
                stringResource(R.string.folder_ignore_delete_caption),
                form.ignoreDelete,
                summary = stringResource(R.string.folder_ignore_delete_description),
            ) {
                actions.onFormChange(form.copy(ignoreDelete = it, changed = true))
            }
            SwitchRow(
                stringResource(R.string.folder_run_script_caption),
                form.runScript,
                summary = stringResource(R.string.folder_run_script_description),
            ) {
                actions.onFormChange(form.copy(runScript = it, changed = true))
            }
        }
    }
}

@Composable
private fun IgnoreListSection(
    form: FolderForm,
    info: FolderScreenInfo,
    actions: FolderScreenActions,
) {
    if (info.createMode) return
    OutlinedTextField(
        value = form.ignoreList,
        onValueChange = {
            actions.onFormChange(
                form.copy(ignoreList = it, ignoreListChanged = true, changed = true)
            )
        },
        label = { Text(stringResource(R.string.ignore_patterns)) },
        enabled = info.canWriteToPath == true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("ignoreList"),
    )
}

/** A setting chosen on another screen: its title, current value and what that means. */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    description: String,
    tag: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun folderTypeTexts(type: String): Triple<Int, Int, ImageVector> =
    when (type) {
        Constants.FOLDER_TYPE_SEND_ONLY ->
            Triple(
                R.string.folder_type_sendonly,
                R.string.folder_type_sendonly_description,
                Icons.Filled.Upload,
            )
        Constants.FOLDER_TYPE_RECEIVE_ONLY ->
            Triple(
                R.string.folder_type_receiveonly,
                R.string.folder_type_receiveonly_description,
                Icons.Filled.Download,
            )
        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED ->
            Triple(
                R.string.folder_type_receive_encrypted,
                R.string.folder_type_receive_encrypted_description,
                Icons.Outlined.Lock,
            )
        else ->
            Triple(
                R.string.folder_type_sendreceive,
                R.string.folder_type_sendreceive_description,
                Icons.Filled.Folder,
            )
    }

private fun pullOrderTexts(order: String?): Pair<Int, Int> =
    when (order) {
        "alphabetic" ->
            R.string.pull_order_type_alphabetic to R.string.pull_order_type_alphabetic_description
        "smallestFirst" ->
            R.string.pull_order_type_smallestFirst to
                R.string.pull_order_type_smallestFirst_description
        "largestFirst" ->
            R.string.pull_order_type_largestFirst to
                R.string.pull_order_type_largestFirst_description
        "oldestFirst" ->
            R.string.pull_order_type_oldestFirst to R.string.pull_order_type_oldestFirst_description
        "newestFirst" ->
            R.string.pull_order_type_newestFirst to R.string.pull_order_type_newestFirst_description
        else -> R.string.pull_order_type_random to R.string.pull_order_type_random_description
    }

@Composable
@ReadOnlyComposable
private fun versioningTitle(form: FolderForm): String =
    stringResource(
        when (form.versioningType) {
            "simple" -> R.string.type_simple
            "trashcan" -> R.string.type_trashcan
            "staggered" -> R.string.type_staggered
            "external" -> R.string.type_external
            else -> R.string.none
        }
    )

@Composable
@ReadOnlyComposable
private fun versioningDescription(form: FolderForm): String {
    val params = form.versioningParams
    return when (form.versioningType) {
        "simple" -> stringResource(R.string.simple_versioning_info, params["keep"].orEmpty())
        "trashcan" ->
            stringResource(R.string.trashcan_versioning_info, params["cleanoutDays"].orEmpty())
        "staggered" -> {
            val maxAgeDays = params["maxAge"]?.toLongOrNull()?.let(TimeUnit.SECONDS::toDays) ?: 0
            stringResource(
                R.string.staggered_versioning_info,
                maxAgeDays.toInt(),
                params["versionsPath"].orEmpty(),
            )
        }
        "external" -> stringResource(R.string.external_versioning_info, params["command"].orEmpty())
        else -> ""
    }
}

private const val DISABLED_ALPHA = 0.38f
private const val SAVING_ALPHA = 0.82f
