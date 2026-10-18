package com.nutomic.syncthingandroid.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService

fun EntryProviderScope<SettingsRoute>.settingsRootEntry(navigator: Navigator<SettingsRoute>) {
    entry<SettingsRoute.Root> { SettingsRootRoute(navigator = navigator) }
}

@Composable
fun SettingsRootRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: SettingsRootViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    SettingsRootScreen(
        navigator = navigator,
        isSyncthingOptionsEnabled = snapshot.state == SyncthingService.State.ACTIVE,
        modifier = modifier,
    )
}

@Composable
fun SettingsRootScreen(
    navigator: Navigator<SettingsRoute>,
    isSyncthingOptionsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier.testTag("settingsRootList"),
        title = stringResource(R.string.settings_title),
    ) {
        item {
            val items: List<@Composable (Shape) -> Unit> = listOf(
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.run_conditions_title),
                        summary = stringResource(R.string.run_conditions_summary),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.RunConditions) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_user_interface),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.UserInterface) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_behaviour),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.Behavior) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_syncthing_options),
                        // Says why the screen is disabled; no need once it is not.
                        summary =
                            if (isSyncthingOptionsEnabled) null
                            else stringResource(R.string.category_syncthing_options_summary),
                        shape = shape,
                        enabled = isSyncthingOptionsEnabled,
                        onClick = { navigator.navigateTo(SettingsRoute.SyncthingOptions) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_backup),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.ImportExport) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_debug),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.Troubleshooting) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_experimental),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.Experimental) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.category_about),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.About) },
                    )
                },
            )
            SettingsGroup(
                items = items,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
