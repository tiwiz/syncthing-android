package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService

/*
 * The navigation rail's extras on tablets and foldables: the add button at its top and, in the
 * expanded rail of a large window, the overflow entries (docs/plans/M3-EXPRESSIVE.md, decision 5).
 */

internal fun NavigationSuiteType.isRail(): Boolean =
    this == NavigationSuiteType.WideNavigationRailCollapsed ||
        this == NavigationSuiteType.WideNavigationRailExpanded

/** The overflow entries, listed under the destinations of the expanded rail. */
@Composable
internal fun RailMenu(state: MainUiState, menu: MenuState, type: NavigationSuiteType) {
    MenuEntries(
        running = state.serviceState == SyncthingService.State.ACTIVE,
        showWebGui = state.showWebGui,
        runningAsService = state.runningAsService,
        actions = menu.actions,
        onChoose = {},
    ) { entries ->
        Column(modifier = Modifier.testTag("railMenu").verticalScroll(rememberScrollState())) {
            RailDivider()
            for (entry in entries) {
                if (entry.apart) RailDivider()
                NavigationSuiteItem(
                    selected = false,
                    onClick = entry.onClick,
                    icon = { Icon(entry.icon, null) },
                    label = { Text(stringResource(entry.label)) },
                    enabled = entry.enabled,
                    navigationSuiteType = type,
                )
            }
        }
    }
}

@Composable
private fun RailDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = RAIL_EDGE.dp, vertical = 8.dp))
}

/** The rail items' room from its start edge; the add button and dividers line up with them. */
private const val RAIL_EDGE = 20

/** The rail's header: the app's name in the expanded rail, then the add button. */
@Composable
internal fun RailHeader(expanded: Boolean, actions: MainActions) {
    Column(modifier = Modifier.testTag("railHeader")) {
        if (expanded) {
            Text(
                stringResource(R.string.fork_app_name),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier =
                    Modifier.padding(start = RAIL_EDGE.dp, end = RAIL_EDGE.dp, bottom = 16.dp),
            )
        }
        RailAddButton(expanded, actions)
    }
}

/** Adds a folder or a device from the top of the rail: a flat button that opens a menu. */
@Composable
private fun RailAddButton(expanded: Boolean, actions: MainActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val add = stringResource(R.string.add)
    Box(modifier = Modifier.padding(start = RAIL_EDGE.dp).testTag("railAdd")) {
        if (expanded) {
            ExtendedFloatingActionButton(
                onClick = { open = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(add) },
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                // The button clears its text's semantics: without this TalkBack hears no name.
                modifier = Modifier.semantics { contentDescription = add },
            )
        } else {
            FloatingActionButton(
                onClick = { open = true },
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
            ) {
                Icon(Icons.Filled.Add, add)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_folder)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                onClick = {
                    open = false
                    actions.onAddFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_device)) },
                leadingIcon = { Icon(Icons.Outlined.Devices, null) },
                onClick = {
                    open = false
                    actions.onAddDevice()
                },
            )
        }
    }
}
