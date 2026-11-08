package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeOffset
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.SpaceModeAction

private const val PANEL_WIDTH = 1024
private const val PANEL_HEIGHT = 720
private const val ORBITER_GAP = 24
private const val RAIL_WIDTH = 240
private const val RAIL_CORNER = 28

/**
 * The main screen in Full Space: [content] in a panel the user can move and resize, with the
 * navigation and the top bar in orbiters around it.
 */
@Composable
internal fun SpatialMain(
    rail: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Subspace {
        SpatialPanel(
            SubspaceModifier.width(PANEL_WIDTH.dp).height(PANEL_HEIGHT.dp).resizable().movable()
        ) {
            content()
            Orbiter(
                position =
                    OrbiterPosition.TopCenter(
                        verticalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                        offset = DpVolumeOffset(y = ORBITER_GAP.dp),
                    )
            ) {
                topBar()
            }
            Orbiter(
                position =
                    OrbiterPosition.CenterStart(
                        horizontalEdgeAlignment = OrbiterPosition.EdgeAlignment.Outside,
                        offset = DpVolumeOffset(x = -ORBITER_GAP.dp),
                    )
            ) {
                rail()
            }
        }
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
            for (destination in MainTab.entries) {
                val selected = destination == tab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { onTabChange(destination) },
                    icon = {
                        Icon(if (selected) destination.selectedIcon else destination.icon, null)
                    },
                    label = { Text(stringResource(destination.title)) },
                    navigationSuiteType = type,
                )
            }
            RailMenu(running, showWebGui, runningAsService, menuActions, type)
        }
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
            SpaceModeAction()
            ServiceChip(serviceState)
            TabActions(tab, actions)
            IconButton(onClick = actions::onSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
            }
        }
    }
}
