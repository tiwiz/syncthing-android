package com.nutomic.syncthingandroid.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsUserInterfaceEntry(
    navigator: Navigator<SettingsRoute>,
) {
    entry<SettingsRoute.UserInterface> {
        SettingsUserInterfaceRoute(navigator = navigator)
    }
}


@Composable
fun SettingsUserInterfaceRoute(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
    viewModel: UserInterfaceViewModel = hiltViewModel(),
) {
    // Keeps the service bound while the screen shows, for the web GUI theme.
    viewModel.snapshot.collectAsStateWithLifecycle()
    SettingsUserInterfaceScreen(
        navigator = navigator,
        onThemeChange = viewModel::syncWebGuiTheme,
        modifier = modifier,
    )
}


@Composable
fun SettingsUserInterfaceScreen(
    navigator: Navigator<SettingsRoute>,
    onThemeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeNames = stringArrayResource(R.array.app_theme_names)
    val themeValues = stringArrayResource(R.array.app_theme_values)

    var theme by rememberPreferenceState(
        Constants.PREF_APP_THEME,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString(),
    )
    val expertMode = rememberPreferenceState(Constants.PREF_EXPERT_MODE, false)
    val startInWebGui = rememberPreferenceState(Constants.PREF_START_INTO_WEB_GUI, false)

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_user_interface),
    ) {
        item {
            val items: List<@Composable (Shape) -> Unit> = listOf(
                { shape ->
                    SettingsListItem(
                        title = stringResource(R.string.preference_app_theme_title),
                        value = theme,
                        values = themeValues.toList(),
                        valueToText = { themeNames[themeValues.indexOf(it)] },
                        shape = shape,
                        onValueChange = { newTheme ->
                            val newThemeInt = newTheme.toIntOrNull()
                            if (newTheme != theme && newThemeInt != null) {
                                theme = newTheme
                                AppCompatDelegate.setDefaultNightMode(newThemeInt)
                                onThemeChange(newTheme)
                            }
                        },
                    )
                },
                { shape ->
                    SettingsSwitch(
                        title = stringResource(R.string.expert_mode_title),
                        summary = stringResource(R.string.expert_mode_summary),
                        checked = expertMode.value,
                        shape = shape,
                        onCheckedChange = { expertMode.value = it },
                    )
                },
                { shape ->
                    SettingsSwitch(
                        title = stringResource(R.string.start_into_web_gui_title),
                        summary = stringResource(R.string.start_into_web_gui_summary),
                        checked = startInWebGui.value,
                        shape = shape,
                        onCheckedChange = { startInWebGui.value = it },
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
