package com.nutomic.syncthingandroid.activities

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.window.core.layout.WindowSizeClass
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.SpaceModeAction
import com.nutomic.syncthingandroid.ui.isSpatialUi
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
    /** The status tab, or null without the service. */
    val status: StatusInfo?,
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
 * (docs/plans/M3-EXPRESSIVE.md). A phone turned sideways gets the rail too: a bar would take height
 * the lists need.
 */
@Composable
private fun navigationType(): NavigationSuiteType {
    val info = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(info)
    val large = info.windowSizeClass.isWidthAtLeastBreakpoint(EXPANDED_RAIL_MIN_WIDTH_DP)
    return when {
        type == NavigationSuiteType.WideNavigationRailCollapsed && large ->
            NavigationSuiteType.WideNavigationRailExpanded
        type == NavigationSuiteType.ShortNavigationBarMedium && shortWindow() ->
            NavigationSuiteType.WideNavigationRailCollapsed
        else -> type
    }
}

/** Whether the window is short, as a phone turned sideways. */
@Composable
internal fun shortWindow(): Boolean =
    !currentWindowAdaptiveInfo()
        .windowSizeClass
        .isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

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
    detail: Detail? = null,
    detailBackProgress: () -> Float = { 0f },
    onTwoPanesChange: (Boolean) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.FOLDERS) }
    val directive = paneDirective()
    val spatial = isSpatialUi
    val besideList = detailBesideList(spatial, directive)
    SideEffect(besideList) { onTwoPanesChange(besideList) }
    val type = navigationType()
    val rail = type.isRail()
    val railExpanded = spatial || type == NavigationSuiteType.WideNavigationRailExpanded
    // The expanded rail lists the menu's entries, so the menu cannot open there: a menu left open
    // (menu key, or a window grown while it was open) would hide nothing and swallow back.
    SideEffect(railExpanded, menuOpen) { if (railExpanded && menuOpen) onMenuOpenChange(false) }
    val menu = MenuState(menuOpen, onMenuOpenChange, menuActions)
    val panes = Panes(directive, besideList, detail, detailBackProgress)
    val running = state.serviceState == SyncthingService.State.ACTIVE
    if (spatial) {
        SpatialMain(
            modifier = modifier,
            rail = {
                SpatialRail(
                    tab = tab,
                    onTabChange = { tab = it },
                    running = running,
                    showWebGui = state.showWebGui,
                    runningAsService = state.runningAsService,
                    menuActions = menuActions,
                    actions = actions,
                )
            },
            topBar = { SpatialTopBar(tab, state.serviceState, actions) },
            list = {
                TabList(state, tab, panes.shownDetail, actions, showTitle = false)
                // In a panel: Subspace hides the main window, and a dialog anchored to it with it.
                state.usageReport?.let { UsageReportingDialog(it, actions) }
            },
            detail =
                if (tab == MainTab.STATUS) {
                    null
                } else {
                    {
                        val shown = panes.detailOf(tab)
                        if (shown == null) TabPlaceholder(tab)
                        else DetailPane(shown, detailBackProgress)
                    }
                },
        )
    } else {
        NavigationSuiteScaffold(
            navigationSuiteType = type,
            modifier = modifier,
            navigationItems = {
                TabItems(tab, onTabChange = { tab = it }, type)
                if (railExpanded) {
                    RailMenu(
                        running = running,
                        showWebGui = state.showWebGui,
                        runningAsService = state.runningAsService,
                        actions = menu.actions,
                        type = type,
                    )
                }
            },
            primaryActionContent = { if (rail) RailHeader(expanded = railExpanded, actions) },
        ) {
            MainScaffold(
                state = state,
                tab = tab,
                panes = panes,
                menu = menu.takeUnless { railExpanded },
                actions = actions,
                addButton = !rail,
                titleInBar = rail && !shortWindow(),
            )
        }
        state.usageReport?.let { UsageReportingDialog(it, actions) }
    }
}

/** Whether a detail opens next to its list: in Full Space each has a panel of its own. */
internal fun detailBesideList(spatial: Boolean, directive: PaneScaffoldDirective): Boolean =
    spatial || directive.twoPanes

/** The list and detail panes of the current window. */
private class Panes(
    val directive: PaneScaffoldDirective,
    val besideList: Boolean,
    val detail: Detail?,
    /** How far a back gesture closing the detail pane has gone, 0 to 1. */
    val backProgress: () -> Float,
) {
    val detailShown: Boolean
        get() = shownDetail != null

    /** The detail next to the list; a detail kept while the window is too narrow is not shown. */
    val shownDetail: Detail?
        get() = detail.takeIf { besideList }

    fun detailOf(tab: MainTab): Detail? =
        when (tab) {
            MainTab.FOLDERS -> detail as? Detail.FolderDetail
            MainTab.DEVICES -> detail as? Detail.DeviceDetail
            MainTab.STATUS -> null
        }
}

/** The overflow menu's state, hoisted to the activity. */
internal class MenuState(
    val open: Boolean,
    val onOpenChange: (Boolean) -> Unit,
    val actions: MenuActions,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    state: MainUiState,
    tab: MainTab,
    panes: Panes,
    /** The overflow menu; null when the expanded rail lists its entries. */
    menu: MenuState?,
    actions: MainActions,
    /** A floating add button; false when the rail has it. */
    addButton: Boolean,
    /** The tab's name is the top bar's title instead of a headline above the list. */
    titleInBar: Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (titleInBar) {
                        Text(
                            stringResource(tab.title),
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            modifier = Modifier.testTag("topBarTitle"),
                        )
                    } else {
                        Text(
                            stringResource(R.string.fork_app_name),
                            modifier = Modifier.testTag("topBarTitle"),
                        )
                    }
                },
                actions = {
                    TopBarActions(tab, state.serviceState, actions)
                    if (menu != null) {
                        MainMenu(
                            open = menu.open,
                            onOpenChange = menu.onOpenChange,
                            running = state.serviceState == SyncthingService.State.ACTIVE,
                            showWebGui = state.showWebGui,
                            runningAsService = state.runningAsService,
                            actions = menu.actions,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (addButton && tab != MainTab.STATUS && !panes.detailShown) AddMenu(actions)
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tab == MainTab.STATUS) {
                TabList(state, tab, panes.shownDetail, actions, showTitle = !titleInBar)
            } else {
                ListWithDetail(
                    directive = panes.directive,
                    detail = panes.detailOf(tab),
                    backProgress = panes.backProgress,
                    placeholder = { TabPlaceholder(tab) },
                ) {
                    TabList(state, tab, panes.shownDetail, actions, showTitle = !titleInBar)
                }
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

/** The space switch, the service state and the actions, in the top bar or its orbiter. */
@Composable
internal fun RowScope.TopBarActions(
    tab: MainTab,
    serviceState: SyncthingService.State,
    actions: MainActions,
) {
    SpaceModeAction()
    ServiceChip(serviceState)
    TabActions(tab, actions)
    IconButton(onClick = actions::onSettings) {
        Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
    }
}

/** One navigation item per destination, for the bar or a rail. */
@Composable
internal fun TabItems(tab: MainTab, onTabChange: (MainTab) -> Unit, type: NavigationSuiteType) {
    for (destination in MainTab.entries) {
        val selected = destination == tab
        NavigationSuiteItem(
            selected = selected,
            onClick = { onTabChange(destination) },
            icon = { Icon(if (selected) destination.selectedIcon else destination.icon, null) },
            label = { Text(stringResource(destination.title)) },
            navigationSuiteType = type,
        )
    }
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
