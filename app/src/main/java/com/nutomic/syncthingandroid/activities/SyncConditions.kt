package com.nutomic.syncthingandroid.activities

import android.content.SharedPreferences
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.edit
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.settings.SettingsScaffold
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference

/**
 * The global run conditions a folder's or device's own conditions can only narrow: what is off
 * globally stays off for the object.
 */
@Immutable
internal data class GlobalRunConditions(
    val wifi: Boolean,
    val whitelist: Boolean,
    val whitelistedSsids: List<String>,
    val meteredWifi: Boolean,
    val mobileData: Boolean,
    val powerSource: String,
) {
    val anyPowerSource: Boolean
        get() = powerSource == Constants.PowerSource.CHARGER_BATTERY

    companion object {
        fun read(preferences: SharedPreferences) =
            GlobalRunConditions(
                wifi = preferences.getBoolean(Constants.PREF_RUN_ON_WIFI, true),
                whitelist = preferences.getBoolean(Constants.PREF_USE_WIFI_SSID_WHITELIST, false),
                whitelistedSsids =
                    preferences
                        .getStringSet(Constants.PREF_WIFI_SSID_WHITELIST, emptySet())
                        .orEmpty()
                        .sorted(),
                meteredWifi = preferences.getBoolean(Constants.PREF_RUN_ON_METERED_WIFI, false),
                mobileData = preferences.getBoolean(Constants.PREF_RUN_ON_MOBILE_DATA, false),
                powerSource =
                    preferences
                        .getString(
                            Constants.PREF_POWER_SOURCE,
                            Constants.PowerSource.CHARGER_BATTERY,
                        )
                        .orEmpty(),
            )
    }
}

/** A folder's or device's own run conditions, by its preference prefix and id. */
@Immutable
internal data class ObjectRunConditions(
    val wifi: Boolean,
    val whitelistedWifi: Boolean,
    val selectedSsids: Set<String>,
    val meteredWifi: Boolean,
    val mobileData: Boolean,
    val powerSource: String,
) {
    /** Saves the conditions; the selected networks only count while the whitelist is on. */
    fun save(preferences: SharedPreferences, prefixAndId: String) {
        preferences.edit {
            putBoolean(Constants.dynPrefObjectSyncOnWifi(prefixAndId), wifi)
            putBoolean(Constants.dynPrefObjectUseWifiSsidWhitelist(prefixAndId), whitelistedWifi)
            putBoolean(Constants.dynPrefObjectSyncOnMeteredWifi(prefixAndId), meteredWifi)
            putBoolean(Constants.dynPrefObjectSyncOnMobileData(prefixAndId), mobileData)
            putString(Constants.dynPrefObjectSyncOnPowerSource(prefixAndId), powerSource)
            putStringSet(
                Constants.dynPrefObjectSelectedWhitelistSsid(prefixAndId),
                if (whitelistedWifi) selectedSsids else emptySet(),
            )
        }
    }

    companion object {
        /** The saved conditions, defaulting to the global ones and limited by them. */
        fun read(
            preferences: SharedPreferences,
            prefixAndId: String,
            global: GlobalRunConditions,
        ): ObjectRunConditions {
            fun narrowed(key: String, globalValue: Boolean) =
                globalValue && preferences.getBoolean(key, globalValue)
            val selected =
                preferences
                    .getStringSet(
                        Constants.dynPrefObjectSelectedWhitelistSsid(prefixAndId),
                        global.whitelistedSsids.toSet(),
                    )
                    .orEmpty()
            return ObjectRunConditions(
                wifi = narrowed(Constants.dynPrefObjectSyncOnWifi(prefixAndId), global.wifi),
                whitelistedWifi =
                    narrowed(
                        Constants.dynPrefObjectUseWifiSsidWhitelist(prefixAndId),
                        global.whitelist,
                    ),
                // Networks no longer on the global whitelist are dropped.
                selectedSsids = selected.intersect(global.whitelistedSsids.toSet()),
                meteredWifi =
                    narrowed(
                        Constants.dynPrefObjectSyncOnMeteredWifi(prefixAndId),
                        global.meteredWifi,
                    ),
                mobileData =
                    narrowed(
                        Constants.dynPrefObjectSyncOnMobileData(prefixAndId),
                        global.mobileData,
                    ),
                powerSource =
                    if (global.anyPowerSource) {
                        preferences
                            .getString(
                                Constants.dynPrefObjectSyncOnPowerSource(prefixAndId),
                                Constants.PowerSource.CHARGER_BATTERY,
                            )
                            .orEmpty()
                    } else {
                        global.powerSource
                    },
            )
        }
    }
}

/** The run conditions of one folder or device, within the global ones. */
@Composable
internal fun SyncConditionsScreen(
    global: GlobalRunConditions,
    conditions: ObjectRunConditions,
    onConditionsChange: (ObjectRunConditions) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val powerSourceNames = stringArrayResource(R.array.power_source_entries)
    val powerSourceValues = stringArrayResource(R.array.power_source_values)
    SettingsScaffold(
        title = stringResource(R.string.custom_sync_conditions_dialog),
        description = stringResource(R.string.run_conditions_summary),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    ) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.run_on_wifi_title)) },
                summary = { Text(stringResource(R.string.run_on_wifi_summary)) },
                value = conditions.wifi,
                onValueChange = { onConditionsChange(conditions.copy(wifi = it)) },
                enabled = global.wifi,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.run_on_whitelisted_wifi_title)) },
                summary = { Text(stringResource(R.string.run_on_whitelisted_wifi_summary)) },
                value = conditions.whitelistedWifi,
                onValueChange = { onConditionsChange(conditions.copy(whitelistedWifi = it)) },
                enabled = global.whitelist && conditions.wifi,
            )
        }
        if (!global.whitelist) {
            item {
                Preference(
                    title = { Text(stringResource(R.string.custom_wifi_ssid_whitelist_empty)) },
                    enabled = false,
                )
            }
        } else {
            for (ssid in global.whitelistedSsids) {
                item(key = "ssid:$ssid") {
                    SwitchPreference(
                        title = { Text(ssid.removePrefix("\"").removeSuffix("\"")) },
                        value = ssid in conditions.selectedSsids,
                        onValueChange = { selected ->
                            val ssids =
                                if (selected) conditions.selectedSsids + ssid
                                else conditions.selectedSsids - ssid
                            onConditionsChange(conditions.copy(selectedSsids = ssids))
                        },
                        enabled = conditions.wifi && conditions.whitelistedWifi,
                    )
                }
            }
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.run_on_metered_wifi_title)) },
                summary = { Text(stringResource(R.string.run_on_metered_wifi_summary)) },
                value = conditions.meteredWifi,
                onValueChange = { onConditionsChange(conditions.copy(meteredWifi = it)) },
                enabled = global.meteredWifi,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.run_on_mobile_data_title)) },
                summary = { Text(stringResource(R.string.run_on_mobile_data_summary)) },
                value = conditions.mobileData,
                onValueChange = { onConditionsChange(conditions.copy(mobileData = it)) },
                enabled = global.mobileData,
            )
        }
        item {
            val name = powerSourceNames.getOrNull(powerSourceValues.indexOf(conditions.powerSource))
            ListPreference(
                title = { Text(stringResource(R.string.power_source_title)) },
                summary = { Text(name.orEmpty()) },
                value = conditions.powerSource,
                onValueChange = { onConditionsChange(conditions.copy(powerSource = it)) },
                values = powerSourceValues.toList(),
                valueToText = { value ->
                    AnnotatedString(
                        powerSourceNames.getOrNull(powerSourceValues.indexOf(value)).orEmpty()
                    )
                },
                enabled = global.anyPowerSource,
            )
        }
    }
}
