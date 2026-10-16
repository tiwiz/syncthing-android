package com.nutomic.syncthingandroid.activities

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.settings.SettingsScaffold
import com.nutomic.syncthingandroid.ui.SectionHeader
import com.nutomic.syncthingandroid.ui.groupedItemShape

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

/** One row of the conditions group: a switch, or a notice without one. */
private sealed interface ConditionsRow {
    val key: String

    /** A condition the user can turn on and off, unless [enabled] is false. */
    data class Switch(
        override val key: String,
        val title: String,
        val summary: String?,
        val checked: Boolean,
        val enabled: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : ConditionsRow

    /** A line the screen shows in place of the whitelisted networks. */
    data class Notice(override val key: String, val text: String) : ConditionsRow
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
    val rows = conditionRows(global, conditions, onConditionsChange)
    SettingsScaffold(
        title = stringResource(R.string.custom_sync_conditions_dialog),
        description = stringResource(R.string.run_conditions_summary),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    ) {
        item(key = "conditions") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    when (row) {
                        is ConditionsRow.Switch ->
                            ConditionSwitchRow(row, groupedItemShape(index, rows.size))
                        is ConditionsRow.Notice ->
                            ConditionNoticeRow(row.text, groupedItemShape(index, rows.size))
                    }
                }
                SectionHeader(stringResource(R.string.power_source_title))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    powerSourceValues.forEachIndexed { index, value ->
                        val checked = value == conditions.powerSource
                        ToggleButton(
                            checked = checked,
                            onCheckedChange = {
                                if (!checked) {
                                    onConditionsChange(conditions.copy(powerSource = value))
                                }
                            },
                            enabled = global.anyPowerSource,
                            shapes =
                                when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    powerSourceValues.lastIndex ->
                                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                            modifier =
                                Modifier.weight(1f).semantics {
                                    role = Role.RadioButton
                                    selected = checked
                                },
                        ) {
                            Text(
                                powerSourceNames.getOrNull(index).orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The condition rows the screen shows: the global ones narrow what it offers. */
@Composable
private fun conditionRows(
    global: GlobalRunConditions,
    conditions: ObjectRunConditions,
    onConditionsChange: (ObjectRunConditions) -> Unit,
): List<ConditionsRow> {
    val rows = mutableListOf<ConditionsRow>()
    rows +=
        ConditionsRow.Switch(
            key = "wifi",
            title = stringResource(R.string.run_on_wifi_title),
            summary = stringResource(R.string.run_on_wifi_summary),
            checked = conditions.wifi,
            enabled = global.wifi,
        ) {
            onConditionsChange(conditions.copy(wifi = it))
        }
    rows +=
        ConditionsRow.Switch(
            key = "whitelist",
            title = stringResource(R.string.run_on_whitelisted_wifi_title),
            summary = stringResource(R.string.run_on_whitelisted_wifi_summary),
            checked = conditions.whitelistedWifi,
            enabled = global.whitelist && conditions.wifi,
        ) {
            onConditionsChange(conditions.copy(whitelistedWifi = it))
        }
    if (!global.whitelist) {
        rows +=
            ConditionsRow.Notice(
                key = "whitelist-empty",
                text = stringResource(R.string.custom_wifi_ssid_whitelist_empty),
            )
    } else {
        for (ssid in global.whitelistedSsids) {
            rows +=
                ConditionsRow.Switch(
                    key = "ssid:$ssid",
                    title = ssid.removePrefix("\"").removeSuffix("\""),
                    summary = null,
                    checked = ssid in conditions.selectedSsids,
                    enabled = conditions.wifi && conditions.whitelistedWifi,
                ) { selected ->
                    val ssids =
                        if (selected) conditions.selectedSsids + ssid
                        else conditions.selectedSsids - ssid
                    onConditionsChange(conditions.copy(selectedSsids = ssids))
                }
        }
    }
    rows +=
        ConditionsRow.Switch(
            key = "metered",
            title = stringResource(R.string.run_on_metered_wifi_title),
            summary = stringResource(R.string.run_on_metered_wifi_summary),
            checked = conditions.meteredWifi,
            enabled = global.meteredWifi,
        ) {
            onConditionsChange(conditions.copy(meteredWifi = it))
        }
    rows +=
        ConditionsRow.Switch(
            key = "mobile",
            title = stringResource(R.string.run_on_mobile_data_title),
            summary = stringResource(R.string.run_on_mobile_data_summary),
            checked = conditions.mobileData,
            enabled = global.mobileData,
        ) {
            onConditionsChange(conditions.copy(mobileData = it))
        }
    return rows
}

/** A condition the user can turn on and off, as a group item; [shape] places it in the group. */
@Composable
private fun ConditionSwitchRow(row: ConditionsRow.Switch, shape: Shape) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .toggleable(
                    value = row.checked,
                    enabled = row.enabled,
                    role = Role.Switch,
                ) {
                    row.onCheckedChange(it)
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
            row.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = row.checked, onCheckedChange = null, enabled = row.enabled)
    }
}

/** A line the screen shows in place of the whitelisted networks, as a group item. */
@Composable
private fun ConditionNoticeRow(text: String, shape: Shape) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                .padding(16.dp),
    )
}
