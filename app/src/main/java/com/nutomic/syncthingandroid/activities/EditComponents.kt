package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.ui.StateBadge
import com.nutomic.syncthingandroid.ui.SyncProgress
import com.nutomic.syncthingandroid.ui.groupedItemShape
import com.nutomic.syncthingandroid.ui.rememberAnimationsEnabled
import com.nutomic.syncthingandroid.ui.stateTextColor
import com.nutomic.syncthingandroid.views.StateTone

// Building blocks the device and folder screens share.

/** The widest a folder or device screen's content gets: forms stay readable on large windows. */
internal const val EDIT_MAX_WIDTH = 840

private const val HERO_BADGE_SIZE = 72
private const val HERO_TITLE_LINES = 2
private const val PERCENT = 100

/** What Syncthing reports about the folder or device a screen edits. */
@Immutable
internal data class EditStatus(
    val tone: StateTone,
    /** Null while Syncthing is not running. */
    val text: String?,
    /** The sync progress in percent, or null when not syncing. */
    val progress: Int?,
)

/**
 * The top of a folder or device screen: its state badge, [title], state and [chips], and while it
 * syncs a card with the progress.
 */
@Composable
internal fun EditHero(
    title: String,
    status: EditStatus,
    chips: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StateBadge(status.tone, size = HERO_BADGE_SIZE.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    maxLines = HERO_TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status.progress == null) {
                    status.text?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = status.tone.stateTextColor,
                            modifier = Modifier.testTag("editState"),
                        )
                    }
                }
                if (chips.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { HeroChip(it) }
                    }
                }
            }
        }
        status.progress?.let { ProgressCard(status.text.orEmpty(), it) }
    }
}

@Composable
private fun HeroChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ProgressCard(state: String, percent: Int) {
    val waveSpeed =
        if (rememberAnimationsEnabled()) WavyProgressIndicatorDefaults.LinearDeterminateWavelength
        else 0.dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(CARD_CORNER.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("editState"),
                )
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SyncProgress(
                progress = percent / PERCENT.toFloat(),
                waveSpeed = waveSpeed,
            )
        }
    }
}

/** An item of a [Group], drawn with its shape in the group. */
internal typealias GroupEntry = @Composable (Shape) -> Unit

/** A group of items: each item gets its shape in the group from [items]'s order. */
@Composable
internal fun Group(items: List<GroupEntry>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp)) {
        items.forEachIndexed { index, item -> item(groupedItemShape(index, items.size)) }
    }
}

/** One item of a [Group], [shape] placing it in the group. */
@Composable
internal fun GroupItem(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/** A switch as a group item; [extra] shows under it, inside the item. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    shape: Shape,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    GroupItem(shape, modifier) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = checked, role = Role.Switch) {
                        onCheckedChange(!checked)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = null)
        }
        extra()
    }
}

/** A group item that opens another screen; dimmed while not [enabled]. */
@Composable
internal fun LinkRow(
    text: String,
    shape: Shape,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupItem(shape) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
            modifier =
                modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(16.dp),
        )
    }
}

/**
 * The buttons at the end of a folder or device screen: [pauseLabel] pauses it (with the form, on
 * saving), [deleteLabel] removes it and is null while creating.
 */
@Composable
internal fun EditButtons(
    pauseLabel: String,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    deleteLabel: String?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalToggleButton(
            checked = paused,
            onCheckedChange = onPausedChange,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Icon(
                Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(pauseLabel, modifier = Modifier.padding(start = 8.dp))
        }
        if (deleteLabel != null) {
            OutlinedButton(
                onClick = onDelete,
                enabled = enabled,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Text(deleteLabel, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    message: Int,
    confirm: Int,
    dismiss: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) } },
    )
}

private const val DISABLED_ALPHA = 0.38f
