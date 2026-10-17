package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.StateBadge
import com.nutomic.syncthingandroid.ui.SyncProgress
import com.nutomic.syncthingandroid.ui.morphingItemShape
import com.nutomic.syncthingandroid.ui.stateTextColor

/**
 * A folder: its state badge, label, path, state, progress and what it offers to do; a tap opens it.
 * It is item [index] of [count] in the grouped list; [selected] while open in the detail pane.
 */
@Composable
internal fun FolderRow(
    item: FolderItem,
    actions: MainActions,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val row = item.row
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    ListRow(
        index = index,
        count = count,
        selected = selected,
        onClick = { actions.onOpenFolder(item.folderId) },
        contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        modifier = modifier.testTag("folder:${row.label}"),
    ) {
        StateBadge(row.tone, modifier = Modifier.padding(top = 4.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium)
            OptionalText(row.directory)
            row.state?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = row.tone.stateTextColor,
                    modifier = Modifier.testTag("folderState:${row.label}"),
                )
            }
            row.progress?.let {
                SyncProgress(
                    progress = it / PERCENT,
                    modifier = Modifier.padding(vertical = 4.dp),
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
        IconButton(onClick = { actions.onOpenFolderInFiles(item.path) }) {
            Icon(folderIcon(row.type), stringResource(R.string.open_file_manager))
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

/**
 * A device: its state badge, name, state, progress, rates, when it was last seen and its folders; a
 * tap opens it. It is item [index] of [count] in the grouped list; [selected] while open in the
 * detail pane.
 */
@Composable
internal fun DeviceRow(
    item: DeviceItem,
    actions: MainActions,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val row = item.row
    ListRow(
        index = index,
        count = count,
        selected = selected,
        onClick = { actions.onOpenDevice(item.deviceId) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.testTag("device:${row.name}"),
    ) {
        StateBadge(row.tone, modifier = Modifier.padding(top = 4.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(
                row.status,
                style = MaterialTheme.typography.labelLarge,
                color = row.tone.stateTextColor,
                modifier = Modifier.testTag("deviceStatus:${row.name}"),
            )
            row.progress?.let {
                SyncProgress(
                    progress = it / PERCENT,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            OptionalText(row.bandwidth)
            OptionalText(row.lastSeen)
            OptionalText(row.sharedFoldersTitle)
            OptionalText(row.sharedFolders)
        }
    }
}

/**
 * A tappable row of a grouped list, item [index] of [count]: its corners morph to fully rounded
 * while it is pressed or [selected], and a selected row stands out with a stronger container.
 */
@Composable
private fun ListRow(
    index: Int,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = morphingItemShape(index, count, emphasized = selected || pressed)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onClick = onClick,
                )
                .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
                .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
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
