package com.nutomic.syncthingandroid.activities

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.SyncProgress
import com.nutomic.syncthingandroid.ui.groupedItemShape
import com.nutomic.syncthingandroid.views.DeviceRowState
import com.nutomic.syncthingandroid.views.FolderRowState

/** A folder in the folder list. */
@Immutable
internal data class FolderItem(val folderId: String, val path: String, val row: FolderRowState)

/** A device in the device list. */
@Immutable internal data class DeviceItem(val deviceId: String, val row: DeviceRowState)

/** Everything the main screen shows. */
@Immutable
internal data class MainUiState(
    val serviceState: SyncthingService.State,
    val folders: List<FolderItem>,
    val devices: List<DeviceItem>,
    val statusLines: List<String>,
    /** One of the Constants.BTNSTATE_* values. */
    val forceStartStop: Int,
    /** The overall progress in percent, or null when hidden. */
    val totalProgress: Int?,
    val runningAsService: Boolean,
    val showWebGui: Boolean,
    /** The usage report to ask about, or null when not asking. */
    val usageReport: String?,
)

/** What the main screen's overflow menu can do; each entry also closes the menu. */
internal interface MenuActions {
    fun onShowDeviceId()

    fun onRecentChanges()

    fun onWebGui()

    fun onBackup()

    fun onRestart()

    fun onExit()
}

/** What the main screen's user can do. */
internal interface MainActions {
    fun onOpenFolder(folderId: String)

    fun onOpenFolderInFiles(path: String)

    fun onOverride(folderId: String)

    fun onRevert(folderId: String)

    fun onOpenDevice(deviceId: String)

    fun onAddFolder()

    fun onRescanAll()

    fun onAddDevice()

    fun onSettings()

    fun onForceStartStop(index: Int)

    fun onExit()

    fun onUsageReporting(answer: UsageAnswer)
}

/** The answers to the usage reporting question. */
internal enum class UsageAnswer {
    YES,
    NO,
    WEBSITE,
}

/** The main screen's destinations, in order. */
internal enum class MainTab(val title: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    FOLDERS(R.string.folders_fragment_title, Icons.Outlined.Folder, Icons.Filled.Folder),
    DEVICES(R.string.devices_fragment_title, Icons.Outlined.Devices, Icons.Filled.Devices),
    STATUS(R.string.status_fragment_title, Icons.Outlined.MonitorHeart, Icons.Filled.MonitorHeart),
}

/** Windows at least this wide get the expanded rail, with labels next to the icons. */
private const val EXPANDED_RAIL_MIN_WIDTH_DP = 1200

/**
 * A short navigation bar on phones, a rail from medium windows and an expanded rail on large ones
 * (docs/plans/M3-EXPRESSIVE.md).
 */
@Composable
private fun navigationType(): NavigationSuiteType {
    val info = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(info)
    val large = info.windowSizeClass.isWidthAtLeastBreakpoint(EXPANDED_RAIL_MIN_WIDTH_DP)
    return if (type == NavigationSuiteType.WideNavigationRailCollapsed && large) {
        NavigationSuiteType.WideNavigationRailExpanded
    } else {
        type
    }
}

/**
 * The main screen: the destinations in a navigation bar or rail, the top bar with the overflow
 * menu, and the exit button.
 */
@Composable
internal fun MainContent(
    state: MainUiState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    menuActions: MenuActions,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.FOLDERS) }
    val type = navigationType()
    NavigationSuiteScaffold(
        navigationSuiteType = type,
        modifier = modifier,
        navigationItems = {
            for (destination in MainTab.entries) {
                val selected = destination == tab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { tab = destination },
                    icon = {
                        Icon(if (selected) destination.selectedIcon else destination.icon, null)
                    },
                    label = { Text(stringResource(destination.title)) },
                    navigationSuiteType = type,
                )
            }
        },
    ) {
        MainScaffold(state, tab, menuOpen, onMenuOpenChange, menuActions, actions)
    }
    state.usageReport?.let { UsageReportingDialog(it, actions) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    state: MainUiState,
    tab: MainTab,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    menuActions: MenuActions,
    actions: MainActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    StatusLight(state.serviceState)
                    TabActions(tab, actions)
                    IconButton(onClick = actions::onSettings) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                    }
                    MainMenu(
                        open = menuOpen,
                        onOpenChange = onMenuOpenChange,
                        running = state.serviceState == SyncthingService.State.ACTIVE,
                        showWebGui = state.showWebGui,
                        runningAsService = state.runningAsService,
                        actions = menuActions,
                    )
                },
            )
        },
        floatingActionButton = { if (tab != MainTab.STATUS) AddMenu(actions) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.FOLDERS -> FolderList(state.folders, state.totalProgress, actions)
                MainTab.DEVICES -> DeviceList(state.devices, actions)
                MainTab.STATUS -> StatusTab(state.statusLines, state.forceStartStop, actions)
            }
        }
    }
}

@Composable
private fun TabActions(tab: MainTab, actions: MainActions) {
    Row {
        when (tab) {
            MainTab.FOLDERS ->
                IconButton(onClick = actions::onRescanAll) {
                    Icon(
                        Icons.Outlined.Refresh,
                        stringResource(R.string.activity_main_bottom_navigation_rescan_all),
                    )
                }
            MainTab.DEVICES,
            MainTab.STATUS -> Unit
        }
    }
}

/** A dot: green while Syncthing runs, orange while it starts, grey otherwise. */
@Composable
private fun StatusLight(state: SyncthingService.State) {
    val (color, tag) =
        when (state) {
            SyncthingService.State.ACTIVE -> colorResource(R.color.text_green) to "light:ACTIVE"
            SyncthingService.State.STARTING ->
                colorResource(R.color.text_orange) to "light:STARTING"
            else -> MaterialTheme.colorScheme.outline to "light:DISABLED"
        }
    Box(
        modifier =
            Modifier.padding(horizontal = 8.dp)
                .size(LIGHT_SIZE.dp)
                .background(color, CircleShape)
                .testTag(tag)
    )
}

/** Adds a folder or a device: a FAB that opens into a menu. */
@Composable
private fun AddMenu(actions: MainActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(checked = expanded, onCheckedChange = { expanded = it }) {
                Icon(
                    if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                    stringResource(R.string.add),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                actions.onAddFolder()
            },
            icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
            text = { Text(stringResource(R.string.add_folder)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                actions.onAddDevice()
            },
            icon = { Icon(Icons.Outlined.Devices, null) },
            text = { Text(stringResource(R.string.add_device)) },
        )
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

/** A list's title, with room above it under the top bar. */
@Composable
private fun ListTitle(tab: MainTab, modifier: Modifier = Modifier) {
    Text(
        stringResource(tab.title),
        style = MaterialTheme.typography.displaySmallEmphasized,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp),
    )
}

@Composable
private fun FolderList(folders: List<FolderItem>, totalProgress: Int?, actions: MainActions) {
    if (folders.isEmpty()) {
        EmptyList(R.string.folder_list_empty)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LIST_BOTTOM_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
    ) {
        item { ListTitle(MainTab.FOLDERS) }
        totalProgress?.let { item { TotalProgressCard(it, Modifier.padding(bottom = 16.dp)) } }
        itemsIndexed(folders, key = { _, folder -> folder.folderId }) { index, item ->
            FolderRow(item, actions, shape = groupedItemShape(index, folders.size))
        }
    }
}

@Composable
private fun DeviceList(devices: List<DeviceItem>, actions: MainActions) {
    if (devices.isEmpty()) {
        EmptyList(R.string.no_devices_configured)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(devices, key = { it.deviceId }) { item ->
            DeviceRow(item, actions)
            HorizontalDivider()
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
private fun StatusTab(lines: List<String>, forceStartStop: Int, actions: MainActions) {
    val titles =
        listOf(
            R.string.button_follow_run_conditions,
            R.string.button_force_start,
            R.string.button_force_stop,
        )
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            titles.forEachIndexed { index, title ->
                SegmentedButton(
                    selected = index == forceStartStop,
                    onClick = { if (index != forceStartStop) actions.onForceStartStop(index) },
                    // Long labels (Italian) wrap to several lines: a pill shape would cut them.
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index,
                            titles.size,
                            baseShape = MaterialTheme.shapes.medium,
                        ),
                    modifier = Modifier.fillMaxHeight().testTag("force:$index"),
                    // The fill shows the choice; a check mark would wrap its label differently.
                    icon = {},
                    label = {
                        Text(
                            stringResource(title).trim(),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        }
        for (line in lines) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("statusLine"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun UsageReportingDialog(report: String, actions: MainActions) {
    AlertDialog(
        onDismissRequest = {},
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

private const val LIGHT_SIZE = 12
private const val PERCENT = 100
private const val CARD_CORNER = 32

/** Room below the last item, so the add button never covers it. */
private const val LIST_BOTTOM_PADDING = 96

/** The gap between the items of a grouped list. */
private const val GROUP_GAP = 3
private const val REPORT_MAX_HEIGHT = 240
