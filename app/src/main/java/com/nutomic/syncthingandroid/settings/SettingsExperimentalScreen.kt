package com.nutomic.syncthingandroid.settings

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsExperimentalEntry(navigator: Navigator<SettingsRoute>) {
    entry<SettingsRoute.Experimental> {
        SettingsExperimentalScreen(navigator = navigator)
    }
}


@Composable
fun SettingsExperimentalScreen(
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val useTor = rememberPreferenceState(Constants.PREF_USE_TOR, false)
    val socksProxy = rememberPreferenceState(Constants.PREF_SOCKS_PROXY_ADDRESS, "")
    val httpProxy = rememberPreferenceState(Constants.PREF_HTTP_PROXY_ADDRESS, "")

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_experimental),
    ) {
        item {
            val items: List<@Composable (Shape) -> Unit> = listOf(
                { shape ->
                    SettingsSwitch(
                        title = stringResource(R.string.use_tor_title),
                        summary = stringResource(R.string.use_tor_summary),
                        checked = useTor.value,
                        shape = shape,
                        onCheckedChange = { useTor.value = it },
                    )
                },
                { shape ->
                    val socksProxySummary = if (socksProxy.value.isBlank())
                        "${stringResource(R.string.do_not_use_proxy)} ${stringResource(R.string.generic_example)}: " +
                            stringResource(R.string.socks_proxy_address_example)
                    else
                        "${stringResource(R.string.use_proxy)} ${socksProxy.value}"
                    SettingsTextFieldItem(
                        title = stringResource(R.string.socks_proxy_address_title),
                        summary = socksProxySummary,
                        value = socksProxy.value,
                        shape = shape,
                        onValueChange = { socksProxy.value = it },
                        textToValue = {
                            validateProxy(
                                newValue = it,
                                regex = Regex("^socks5://.*:\\d{1,5}$"),
                                errorResId = R.string.toast_invalid_socks_proxy_address,
                                context = context,
                            )
                        },
                        enabled = !useTor.value,
                    )
                },
                { shape ->
                    val httpProxySummary = if (httpProxy.value.isBlank())
                        "${stringResource(R.string.do_not_use_proxy)} ${stringResource(R.string.generic_example)}: " +
                            stringResource(R.string.http_proxy_address_example)
                    else
                        "${stringResource(R.string.use_proxy)} ${httpProxy.value}"
                    SettingsTextFieldItem(
                        title = stringResource(R.string.http_proxy_address_title),
                        summary = httpProxySummary,
                        value = httpProxy.value,
                        shape = shape,
                        onValueChange = { httpProxy.value = it },
                        textToValue = {
                            validateProxy(
                                newValue = it,
                                regex = Regex("^https?://.*:\\d{1,5}$"),
                                errorResId = R.string.toast_invalid_http_proxy_address,
                                context = context,
                            )
                        },
                        enabled = !useTor.value,
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

private fun validateProxy(
    newValue: String,
    regex: Regex,
    @StringRes errorResId: Int,
    context: Context
): String? {
    return when {
        newValue.isEmpty() -> newValue
        newValue.matches(regex) -> newValue
        else -> {
            Toast.makeText(context, errorResId, Toast.LENGTH_LONG).show()
            null
        }
    }
}
