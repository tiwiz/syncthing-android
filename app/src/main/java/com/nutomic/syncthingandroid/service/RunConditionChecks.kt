package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import com.nutomic.syncthingandroid.R

/** Whether a run condition is met, and the line it adds to the explanation shown to the user. */
internal class ConditionResult(val conditionMet: Boolean, val explanation: String = "")

/**
 * The run conditions RunConditionMonitor combines: each one reads its preference (global, or of a
 * folder or device) and the device state.
 */
internal class RunConditionChecks(
    context: Context,
    private val preferences: SharedPreferences,
    private val logV: (String) -> Unit,
) {
    private val res = context.resources
    val deviceState = DeviceState(context, logV)

    /** Constants.PREF_RUN_ON_WIFI */
    fun syncOnWifi(prefName: String): ConditionResult {
        if (!preferences.getBoolean(prefName, true)) {
            return ConditionResult(false, "\n" + res.getString(R.string.reason_wifi_disallowed))
        }
        if (deviceState.isWifiOrEthernetConnection()) {
            return ConditionResult(true, "\n" + res.getString(R.string.reason_on_wifi))
        }
        // Not "false" right away: the flight mode workaround some phones need is done later.
        // ConnectivityManager.getActiveNetworkInfo() returns null on those phones, so they look
        // offline even if connected to wifi during flight mode.
        return ConditionResult(false, "\n" + res.getString(R.string.reason_not_on_wifi))
    }

    fun syncOnPowerSource(prefName: String): ConditionResult =
        when (preferences.getString(prefName, Constants.PowerSource.CHARGER_BATTERY)) {
            Constants.PowerSource.CHARGER ->
                if (!deviceState.isCharging())
                    ConditionResult(false, res.getString(R.string.reason_not_charging))
                else ConditionResult(true)
            Constants.PowerSource.BATTERY ->
                if (deviceState.isCharging())
                    ConditionResult(false, res.getString(R.string.reason_not_on_battery_power))
                else ConditionResult(true)
            else -> ConditionResult(true)
        }

    /** Constants.PREF_WIFI_SSID_WHITELIST */
    fun syncOnWhitelistedWifi(
        prefNameUseWhitelist: String,
        prefNameSelectedSsids: String,
    ): ConditionResult {
        // Precondition: an active wifi connection has been detected.
        if (!preferences.getBoolean(prefNameUseWhitelist, false)) {
            logV("handleWifiWhitelist: !prefWifiWhitelistEnabled")
            return ConditionResult(true, "\n" + res.getString(R.string.reason_on_whitelisted_wifi))
        }
        val whitelistedSsids = preferences.getStringSet(prefNameSelectedSsids, HashSet()).orEmpty()
        return when (deviceState.isWifiConnectionWhitelisted(whitelistedSsids)) {
            true -> {
                logV("handleWifiWhitelist: isWifiConnectionWhitelisted")
                ConditionResult(true, "\n" + res.getString(R.string.reason_on_whitelisted_wifi))
            }
            false ->
                ConditionResult(
                    false,
                    "\n" + res.getString(R.string.reason_not_on_whitelisted_wifi),
                )
            null ->
                ConditionResult(false, "\n" + res.getString(R.string.reason_location_unavailable))
        }
    }

    /** Constants.PREF_RUN_ON_METERED_WIFI */
    fun syncOnMeteredWifi(prefName: String): ConditionResult =
        when {
            // Always met: both types of wifi, metered and non-metered, are allowed.
            preferences.getBoolean(prefName, false) ->
                ConditionResult(
                    true,
                    "\n" + res.getString(R.string.reason_on_metered_nonmetered_wifi),
                )
            !deviceState.isMeteredNetworkConnection() ->
                ConditionResult(true, "\n" + res.getString(R.string.reason_on_nonmetered_wifi))
            // Only non-metered wifi is allowed, and this one is metered.
            else ->
                ConditionResult(false, "\n" + res.getString(R.string.reason_not_nonmetered_wifi))
        }

    /** Constants.PREF_RUN_ON_MOBILE_DATA */
    fun syncOnMobileData(prefName: String): ConditionResult =
        when {
            !preferences.getBoolean(prefName, false) ->
                ConditionResult(false, res.getString(R.string.reason_mobile_data_disallowed))
            deviceState.isMobileDataConnection() ->
                ConditionResult(true, res.getString(R.string.reason_on_mobile_data))
            else -> ConditionResult(false, res.getString(R.string.reason_not_on_mobile_data))
        }

    /** Constants.PREF_RUN_ON_ROAMING */
    fun syncOnRoaming(prefName: String): ConditionResult =
        when {
            // Always met: both roaming and non-roaming mobile data are allowed.
            preferences.getBoolean(prefName, false) ->
                ConditionResult(
                    true,
                    "\n" + res.getString(R.string.reason_on_roaming_nonroaming_mobile_data),
                )
            !deviceState.isRoamingNetworkConnection() ->
                ConditionResult(
                    true,
                    "\n" + res.getString(R.string.reason_on_nonroaming_mobile_data),
                )
            // Only non-roaming mobile data is allowed, and this network is roaming.
            else ->
                ConditionResult(
                    false,
                    "\n" + res.getString(R.string.reason_not_nonroaming_mobile_data),
                )
        }

    /**
     * Whether a folder's or device's own sync conditions are met. Precondition: the object has
     * "...CustomSyncConditionsEnabled == true".
     */
    fun checkObjectSyncConditions(objectPrefixAndId: String): Boolean {
        if (
            !syncOnPowerSource(Constants.dynPrefObjectSyncOnPowerSource(objectPrefixAndId))
                .conditionMet
        ) {
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnPowerSource")
            return false
        }
        if (
            syncOnMobileData(Constants.dynPrefObjectSyncOnMobileData(objectPrefixAndId))
                .conditionMet
        ) {
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnMobileData")
            return true
        }
        return syncOnWifi(Constants.dynPrefObjectSyncOnWifi(objectPrefixAndId)).conditionMet &&
            syncOnMeteredWifi(Constants.dynPrefObjectSyncOnMeteredWifi(objectPrefixAndId))
                .conditionMet &&
            syncOnWhitelistedWifi(
                    Constants.dynPrefObjectUseWifiSsidWhitelist(objectPrefixAndId),
                    Constants.dynPrefObjectSelectedWhitelistSsid(objectPrefixAndId),
                )
                .conditionMet
    }
}
