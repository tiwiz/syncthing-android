package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.AppAlertDialog
import com.nutomic.syncthingandroid.ui.SyncProgress

/** What [tab] lists; [shown] is the detail open next to it, marked in the list. */
@Composable
internal fun TabList(
    state: MainUiState,
    tab: MainTab,
    shown: Detail?,
    actions: MainActions,
    showTitle: Boolean,
    showRunMode: Boolean = true,
) {
    when (tab) {
        MainTab.FOLDERS ->
            FolderList(
                state.folders,
                state.totalProgress,
                actions,
                showTitle = showTitle,
                selectedFolderId =
                    (shown as? Detail.FolderDetail)?.key?.removePrefix(DetailSelection.FOLDER),
            )
        MainTab.DEVICES ->
            DeviceList(
                state.devices,
                actions,
                showTitle = showTitle,
                selectedDeviceId =
                    (shown as? Detail.DeviceDetail)?.key?.removePrefix(DetailSelection.DEVICE),
            )
        MainTab.STATUS ->
            StatusTab(state.status, state.forceStartStop, actions, showTitle, showRunMode)
    }
}

/** The detail pane of [tab] while nothing is open in it. */
@Composable
internal fun TabPlaceholder(tab: MainTab) {
    when (tab) {
        MainTab.FOLDERS -> DetailPlaceholder(Icons.Outlined.Folder, R.string.fork_choose_folder)
        MainTab.DEVICES -> DetailPlaceholder(Icons.Outlined.Devices, R.string.fork_choose_device)
        MainTab.STATUS -> Unit
    }
}

/** The overall progress of the devices in sync, above the folders. */
@Composable
private fun TotalProgressCard(percent: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(CARD_CORNER.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    if (percent < PERCENT) R.string.state_syncing_general
                    else R.string.state_up_to_date
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "$percent%",
                style = MaterialTheme.typography.displayMediumEmphasized,
                modifier = Modifier.testTag("totalProgress"),
            )
            SyncProgress(progress = percent / PERCENT.toFloat())
        }
    }
}

/** The room under the top bar when the top bar names the tab and the list has no headline. */
@Composable
internal fun NoTitleGap() {
    Spacer(modifier = Modifier.height(8.dp))
}

/** A list's title, with room above it under the top bar; smaller in a short window. */
@Composable
internal fun ListTitle(tab: MainTab, modifier: Modifier = Modifier) {
    val short = shortWindow()
    Text(
        stringResource(tab.title),
        style =
            if (short) MaterialTheme.typography.titleLargeEmphasized
            else MaterialTheme.typography.displaySmallEmphasized,
        modifier =
            modifier
                .padding(start = 8.dp, top = 8.dp, bottom = if (short) 8.dp else 16.dp)
                .testTag("listTitle"),
    )
}

@Composable
private fun FolderList(
    folders: List<FolderItem>,
    totalProgress: Int?,
    actions: MainActions,
    showTitle: Boolean,
    selectedFolderId: String?,
) {
    if (folders.isEmpty()) {
        EmptyList(R.string.folder_list_empty)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LIST_BOTTOM_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
    ) {
        item { if (showTitle) ListTitle(MainTab.FOLDERS) else NoTitleGap() }
        totalProgress?.let { item { TotalProgressCard(it, Modifier.padding(bottom = 16.dp)) } }
        itemsIndexed(folders, key = { _, folder -> folder.folderId }) { index, item ->
            FolderRow(
                item = item,
                actions = actions,
                index = index,
                count = folders.size,
                selected = item.folderId == selectedFolderId,
            )
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<DeviceItem>,
    actions: MainActions,
    showTitle: Boolean,
    selectedDeviceId: String?,
) {
    if (devices.isEmpty()) {
        EmptyList(R.string.no_devices_configured)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LIST_BOTTOM_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
    ) {
        item { if (showTitle) ListTitle(MainTab.DEVICES) else NoTitleGap() }
        itemsIndexed(devices, key = { _, device -> device.deviceId }) { index, item ->
            DeviceRow(
                item = item,
                actions = actions,
                index = index,
                count = devices.size,
                selected = item.deviceId == selectedDeviceId,
            )
        }
    }
}

@Composable
private fun EmptyList(text: Int) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun UsageReportingDialog(report: String, actions: MainActions) {
    AppAlertDialog(
        onDismissRequest = {},
        dismissible = false,
        title = { Text(stringResource(R.string.usage_reporting_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.usage_reporting_dialog_description))
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).heightIn(max = REPORT_MAX_HEIGHT.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onUsageReporting(UsageAnswer.YES) }) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.onUsageReporting(UsageAnswer.WEBSITE) }) {
                    Text(stringResource(R.string.open_website))
                }
                TextButton(onClick = { actions.onUsageReporting(UsageAnswer.NO) }) {
                    Text(stringResource(R.string.no))
                }
            }
        },
    )
}

private const val PERCENT = 100
internal const val CARD_CORNER = 32

/** Room below the last item, so the add button never covers it. */
private const val LIST_BOTTOM_PADDING = 96

/** The gap between the items of a grouped list. */
internal const val GROUP_GAP = 3
private const val REPORT_MAX_HEIGHT = 240
