package com.nutomic.syncthingandroid.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R


fun EntryProviderScope<SettingsRoute>.settingsAboutEntry(navigator: Navigator<SettingsRoute>) {
    entry<SettingsRoute.About> { SettingsAboutRoute(navigator = navigator) }
}


@Composable
fun SettingsAboutRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsAboutScreen(navigator = navigator, state = state, modifier = modifier)
}


@Composable
fun SettingsAboutScreen(
    navigator: Navigator<SettingsRoute>,
    state: AboutState,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_about),
    ) {
        item {
            val items: List<@Composable (Shape) -> Unit> = listOf(
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.app_version_title),
                        summary = state.appVersion,
                        shape = shape,
                        onClick = {},
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.syncthing_version_title),
                        summary = state.coreVersion,
                        shape = shape,
                        onClick = {},
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.syncthing_database_size),
                        summary = state.dbSize,
                        shape = shape,
                        onClick = {},
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.os_open_file_limit),
                        summary = state.fileLimit,
                        shape = shape,
                        onClick = {},
                    )
                },
                { shape ->
                    val stForumUri = stringResource(R.string.syncthing_forum_url)
                    SettingsItem(
                        title = stringResource(R.string.syncthing_forum_title),
                        summary = stringResource(R.string.syncthing_forum_summary),
                        shape = shape,
                        onClick = { uriHandler.openUri(stForumUri) },
                    )
                },
                { shape ->
                    val stPrivacyPolicyUri = stringResource(R.string.privacy_policy_url)
                    SettingsItem(
                        title = stringResource(R.string.privacy_title),
                        summary = stringResource(R.string.privacy_summary),
                        shape = shape,
                        onClick = { uriHandler.openUri(stPrivacyPolicyUri) },
                    )
                },
                { shape ->
                    SettingsItem(
                        title = stringResource(R.string.open_source_licenses_title),
                        summary = stringResource(R.string.open_source_licenses_summary),
                        shape = shape,
                        onClick = { navigator.navigateTo(SettingsRoute.Licenses) },
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
