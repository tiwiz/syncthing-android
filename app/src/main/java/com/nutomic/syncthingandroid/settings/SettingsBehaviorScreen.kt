package com.nutomic.syncthingandroid.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsBehaviorEntry(navigator: Navigator<SettingsRoute>) {
    entry<SettingsRoute.Behavior> {
        SettingsBehaviorScreen(navigator = navigator)
    }
}


@Composable
fun SettingsBehaviorScreen(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
) {

    val autoStart = rememberPreferenceState(Constants.PREF_START_SERVICE_ON_BOOT, false)
    val broadcast = rememberPreferenceState(Constants.PREF_BROADCAST_SERVICE_CONTROL, false)
    val overwrite = rememberPreferenceState(Constants.PREF_ALLOW_OVERWRITE_FILES, false)

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_behaviour),
    ) {
        item {
            val items: List<@Composable (Shape) -> Unit> = listOf(
                { shape ->
                    SettingsSwitch(
                        title = stringResource(R.string.behaviour_autostart_title),
                        summary = stringResource(R.string.behaviour_autostart_summary),
                        checked = autoStart.value,
                        shape = shape,
                        onCheckedChange = { autoStart.value = it },
                    )
                },
                { shape ->
                    SettingsSwitch(
                        title = stringResource(R.string.broadcast_service_control_title),
                        summary = stringResource(R.string.broadcast_service_control_summary),
                        checked = broadcast.value,
                        shape = shape,
                        onCheckedChange = { broadcast.value = it },
                    )
                },
                { shape ->
                    SettingsSwitch(
                        title = stringResource(R.string.allow_overwrite_files_title),
                        summary = stringResource(R.string.allow_overwrite_files_summary),
                        checked = overwrite.value,
                        shape = shape,
                        onCheckedChange = { overwrite.value = it },
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
