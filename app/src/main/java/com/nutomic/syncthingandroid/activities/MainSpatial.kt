package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SpatialArrangement
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.unit.DpVolumeOffset
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.movablePanel

private const val LIST_WIDTH = 440
private const val DETAIL_WIDTH = 600
private const val ALONE_WIDTH = 720
private const val PANEL_GAP = 16
private const val PANEL_TOP = 16
private const val PANEL_HEIGHT = 720

/** The rail still fits an orbiter around a panel of this height. */
private const val MIN_HEIGHT = 640
private const val MIN_LIST_WIDTH = 320
private const val MIN_DETAIL_WIDTH = 400
private const val ORBITER_GAP = 24
private const val RAIL_WIDTH = 240
private const val RAIL_CORNER = 28
private const val STATUS_BAR_WIDTH = 440

/**
 * The main screen in Full Space: [list] and [detail] in panels the user can move and resize, with
 * the navigation in an orbiter on the list's panel and the top bar's content above both. Without a
 * [detail], as on the status tab, the list's panel is wider.
 */
@Composable
internal fun SpatialMain(
    rail: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    statusBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Subspace {
        SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(PANEL_GAP.dp)) {
            Orbiter(
                position =
                    OrbiterPosition.TopCenter(
                        verticalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                        offset = DpVolumeOffset(y = ORBITER_GAP.dp),
                    ),
                shape = SpatialRoundedCornerShape(CircleShape.topStart),
            ) {
                topBar()
            }
            Orbiter(
                position =
                    OrbiterPosition.BottomCenter(
                        verticalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                        offset = DpVolumeOffset(y = -ORBITER_GAP.dp),
                    ),
                shape = SpatialRoundedCornerShape(CircleShape.topStart),
            ) {
                statusBar()
            }
            // A user's resize would outlive the layout it was made for: each layout starts afresh.
            key(detail == null) {
                SpatialPanel(
                    if (detail == null) panelModifier(ALONE_WIDTH, ALONE_WIDTH)
                    else panelModifier(LIST_WIDTH, MIN_LIST_WIDTH)
                ) {
                    PanelSurface(modifier, list)
                    Orbiter(
                        position =
                            OrbiterPosition.CenterStart(
                                horizontalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                                offset = DpVolumeOffset(x = -ORBITER_GAP.dp),
                            ),
                        shape = SpatialRoundedCornerShape(CornerSize(RAIL_CORNER.dp)),
                    ) {
                        rail()
                    }
                }
            }
            if (detail != null) {
                SpatialPanel(panelModifier(DETAIL_WIDTH, MIN_DETAIL_WIDTH)) {
                    PanelSurface(content = detail)
                }
            }
        }
    }
}

private fun panelModifier(width: Int, minWidth: Int): SubspaceModifier =
    movablePanel(width.dp, PANEL_HEIGHT.dp, minWidth.dp, MIN_HEIGHT.dp)

/** A panel draws nothing of its own, so the content gets the screen's background here. */
@Composable
private fun PanelSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(top = PANEL_TOP.dp)) { content() }
    }
}

/** The destinations, the add button and the overflow entries, as in the expanded rail. */
@Composable
internal fun SpatialRail(
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    running: Boolean,
    showWebGui: Boolean,
    runningAsService: Boolean,
    menuActions: MenuActions,
    confirmations: MenuConfirmations,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    val type = NavigationSuiteType.WideNavigationRailExpanded
    Surface(
        shape = RoundedCornerShape(RAIL_CORNER.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.width(RAIL_WIDTH.dp).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RailHeader(expanded = true, actions)
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
            TabItems(tab, onTabChange, type)
            RailMenu(running, showWebGui, runningAsService, menuActions, confirmations, type)
        }
    }
}

/** The run mode, always at hand: on a phone or tablet it is on the status tab. */
@Composable
internal fun SpatialStatusBar(
    forceStartStop: Int,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        RunModeButtons(
            forceStartStop,
            actions,
            modifier = Modifier.width(STATUS_BAR_WIDTH.dp).padding(8.dp),
        )
    }
}

/** The tab's name, the space switch, the service state and the tab's actions. */
@Composable
internal fun SpatialTopBar(
    tab: MainTab,
    serviceState: SyncthingService.State,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(tab.title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(end = 16.dp).testTag("topBarTitle"),
            )
            TopBarActions(tab, serviceState, actions)
        }
    }
}
