package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/** The device state the run conditions look at: power, network and wifi. */
@Suppress("DEPRECATION") // NetworkInfo and WifiInfo: the conditions were written against them.
internal class DeviceState(private val context: Context, private val logV: (String) -> Unit) {

    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            logV("isCharging: Checking battery status intent returned null")
            return false
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        logV("isCharging: Battery status intent extras: ${intent.extras ?: "null"}")
        val chargers =
            mutableSetOf(
                BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.BATTERY_PLUGGED_USB,
                BatteryManager.BATTERY_PLUGGED_WIRELESS,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            chargers += BatteryManager.BATTERY_PLUGGED_DOCK
        }
        return plugged in chargers
    }

    fun isPowerSaving(): Boolean =
        context.getSystemService(PowerManager::class.java).isPowerSaveMode

    fun isFlightMode(): Boolean = connectivity().activeNetworkInfo == null

    private fun connectivity() = context.getSystemService(ConnectivityManager::class.java)

    /** The active network if connected; null in flight mode or without a connection. */
    private fun connectedNetwork() = connectivity().activeNetworkInfo?.takeIf { it.isConnected }

    fun isMeteredNetworkConnection(): Boolean {
        val ni = connectedNetwork() ?: return false
        // Wi-Fi and Ethernet count as "Wi-Fi". Ethernet counts as un-metered to allow syncing on
        // Android TV or a VirtualBox Ethernet connection.
        if (ni.type == ConnectivityManager.TYPE_ETHERNET) return false
        return connectivity().isActiveNetworkMetered
    }

    fun isMobileDataConnection(): Boolean =
        connectedNetwork()?.type in
            setOf(
                ConnectivityManager.TYPE_BLUETOOTH,
                ConnectivityManager.TYPE_MOBILE,
                ConnectivityManager.TYPE_MOBILE_DUN,
                ConnectivityManager.TYPE_MOBILE_HIPRI,
            )

    fun isRoamingNetworkConnection(): Boolean = connectedNetwork()?.isRoaming == true

    fun isWifiOrEthernetConnection(): Boolean =
        connectedNetwork()?.type in
            setOf(
                ConnectivityManager.TYPE_WIFI,
                ConnectivityManager.TYPE_WIMAX,
                ConnectivityManager.TYPE_ETHERNET,
            )

    /**
     * Whether the connected wifi is in [whitelistedSsids]; null when the SSID is unknown, which
     * means location is unavailable.
     */
    fun isWifiConnectionWhitelisted(whitelistedSsids: Set<String>): Boolean? {
        val wifiInfo =
            context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        if (wifiInfo == null) {
            // May be null, if wifi has been turned off in the meantime.
            Log.d(TAG, "isWifiConnectionWhitelisted: SSID unknown due to wifiInfo == null")
            return false
        }
        val wifiSsid = wifiInfo.ssid
        if (wifiSsid == null || wifiSsid == "<unknown ssid>") {
            Log.d(
                TAG,
                "isWifiConnectionWhitelisted: Got null SSID. Try to enable android location service.",
            )
            return null
        }
        return wifiSsid in whitelistedSsids
    }

    private companion object {
        const val TAG = "RunConditionMonitor"
    }
}
