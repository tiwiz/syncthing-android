package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.views.DeviceRowState
import com.nutomic.syncthingandroid.views.FolderRowState
import kotlinx.coroutines.launch

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
    val exitVisible: Boolean,
    val runningAsService: Boolean,
    val showWebGui: Boolean,
    /** The usage report to ask about, or null when not asking. */
    val usageReport: String?,
)

/** What the drawer can do; each entry also closes the drawer, except exit. */
internal interface DrawerActions {
    fun onShowDeviceId()

    fun onRecentChanges()

    fun onWebGui()

    fun onBackup()

    fun onRestart()

    fun onSettings()

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

/** The main screen's tabs, in order. */
internal enum class MainTab(val title: Int) {
    FOLDERS(R.string.folders_fragment_title),
    DEVICES(R.string.devices_fragment_title),
    STATUS(R.string.status_fragment_title),
}

/** The main screen: the drawer around the top bar, the three tabs and the exit button. */
@Composable
internal fun MainContent(
    state: MainUiState,
    drawerState: DrawerState,
    requestDrawerFocus: Boolean,
    drawerActions: DrawerActions,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            MainDrawer(
                running = state.serviceState == SyncthingService.State.ACTIVE,
                showWebGui = state.showWebGui,
                runningAsService = state.runningAsService,
                requestFocus = requestDrawerFocus,
                actions = drawerActions,
            )
        },
    ) {
        MainScaffold(state, drawerState, actions)
    }
    state.usageReport?.let { UsageReportingDialog(it, actions) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(state: MainUiState, drawerState: DrawerState, actions: MainActions) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { MainTab.entries.size }
    val tab = MainTab.entries[pagerState.currentPage]
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, stringResource(R.string.open_main_menu))
                        }
                    },
                    actions = {
                        state.totalProgress?.let { TotalProgress(it) }
                        StatusLight(state.serviceState)
                        TabActions(tab, actions)
                    },
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    MainTab.entries.forEachIndexed { index, mainTab ->
                        Tab(
                            selected = index == pagerState.currentPage,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(stringResource(mainTab.title)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.exitVisible) {
                FloatingActionButton(onClick = actions::onExit) {
                    Icon(Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.exit))
                }
            }
        },
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding)) {
            page ->
            when (MainTab.entries[page]) {
                MainTab.FOLDERS -> FolderList(state.folders, actions)
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
            MainTab.FOLDERS -> {
                IconButton(onClick = actions::onRescanAll) {
                    Icon(
                        Icons.Outlined.Refresh,
                        stringResource(R.string.activity_main_bottom_navigation_rescan_all),
                    )
                }
                IconButton(onClick = actions::onAddFolder) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.add_folder))
                }
            }
            MainTab.DEVICES ->
                IconButton(onClick = actions::onAddDevice) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.add_device))
                }
            MainTab.STATUS ->
                IconButton(onClick = actions::onSettings) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                }
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

@Composable
private fun TotalProgress(percent: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(PROGRESS_SIZE.dp)) {
        CircularProgressIndicator(
            progress = { percent / PERCENT },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            percent.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("totalProgress"),
        )
    }
}

@Composable
private fun FolderList(folders: List<FolderItem>, actions: MainActions) {
    if (folders.isEmpty()) {
        EmptyList(R.string.folder_list_empty)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(folders, key = { it.folderId }) { item ->
            FolderRow(item, actions)
            HorizontalDivider()
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
                    shape = SegmentedButtonDefaults.itemShape(index, titles.size),
                    modifier = Modifier.fillMaxHeight().testTag("force:$index"),
                    // The fill shows the choice; a check mark would wrap its label differently.
                    icon = {},
                    label = { Text(stringResource(title).trim(), textAlign = TextAlign.Center) },
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
private const val PROGRESS_SIZE = 32
private const val PERCENT = 100f
private const val REPORT_MAX_HEIGHT = 240
