package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants

/** A folder: label, path, state, progress and what it offers to do; a tap opens it. */
@Composable
internal fun FolderRow(item: FolderItem, actions: MainActions, modifier: Modifier = Modifier) {
    val row = item.row
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { actions.onOpenFolder(item.folderId) }
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .testTag("folder:${row.label}")
    ) {
        IconButton(onClick = { actions.onOpenFolderInFiles(item.path) }) {
            Icon(folderIcon(row.type), stringResource(R.string.open_file_manager))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium)
            OptionalText(row.directory)
            row.state?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(row.stateColor),
                    modifier = Modifier.testTag("folderState:${row.label}"),
                )
            }
            row.progress?.let {
                LinearProgressIndicator(
                    progress = { it / PERCENT },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OptionalText(row.items)
            OptionalText(row.conflicts, colorResource(R.color.text_red))
            OptionalText(row.lastItemFinished)
            OptionalText(row.lastItemFinishedTime)
            OptionalText(row.invalid, colorResource(R.color.text_red))
            if (row.showOverride) {
                OutlinedButton(onClick = { confirm = OVERRIDE }) {
                    Text(stringResource(R.string.override_changes))
                }
            }
            if (row.showRevert) {
                OutlinedButton(onClick = { confirm = REVERT }) {
                    Text(stringResource(row.revertLabel))
                }
            }
        }
    }
    confirm?.let { what ->
        val override = what == OVERRIDE
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        if (override) R.string.override_changes else R.string.revert_local_changes
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (override) R.string.override_changes_question
                        else R.string.revert_local_changes_question
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        if (override) actions.onOverride(item.folderId)
                        else actions.onRevert(item.folderId)
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(android.R.string.no))
                }
            },
        )
    }
}

/** A device: name, when it was last seen, its folders, state and rates; a tap opens it. */
@Composable
internal fun DeviceRow(item: DeviceItem, actions: MainActions, modifier: Modifier = Modifier) {
    val row = item.row
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { actions.onOpenDevice(item.deviceId) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("device:${row.name}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(row.name, style = MaterialTheme.typography.titleMedium)
        row.progress?.let {
            LinearProgressIndicator(progress = { it / PERCENT }, modifier = Modifier.fillMaxWidth())
        }
        OptionalText(row.lastSeen)
        OptionalText(row.sharedFoldersTitle)
        OptionalText(row.sharedFolders)
        OptionalText(row.bandwidth)
        Text(
            row.status,
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(row.statusColor),
            modifier = Modifier.testTag("deviceStatus:${row.name}"),
        )
    }
}

/** Shows [text] in [color], or nothing without text. */
@Composable
private fun OptionalText(text: String?, color: Color = Color.Unspecified) {
    if (!text.isNullOrEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
                else color,
        )
    }
}

private fun folderIcon(type: String): ImageVector =
    when (type) {
        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Icons.Outlined.Lock
        Constants.FOLDER_TYPE_RECEIVE_ONLY -> Icons.Filled.Download
        Constants.FOLDER_TYPE_SEND_ONLY -> Icons.Filled.Upload
        else -> Icons.Filled.Folder
    }

private const val OVERRIDE = "override"
private const val REVERT = "revert"
private const val PERCENT = 100f
