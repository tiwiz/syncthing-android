package com.nutomic.syncthingandroid.service

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.Golden
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo

/**
 * Characterizes the "should Syncthing run?" decision for a matrix of device states and preferences.
 * Each scenario builds a fresh [RunConditionMonitor], which decides in its constructor, and records
 * the decision, the explanation shown to the user, and whether the per-folder/device conditions
 * were re-applied.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowContentResolverWithStatusListeners::class])
@Suppress("DEPRECATION") // The monitor itself uses NetworkInfo.
class RunConditionMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private class Scenario(val name: String, val setup: Device.() -> Unit)

    /** The knobs a scenario can turn. */
    private inner class Device {
        fun network(type: Int?, roaming: Boolean = false) {
            val info = type?.let {
                ShadowNetworkInfo.newInstance(
                        NetworkInfo.DetailedState.CONNECTED,
                        it,
                        0,
                        true,
                        NetworkInfo.State.CONNECTED,
                    )
                    .also { ni ->
                        NetworkInfo::class
                            .java
                            .getMethod("setRoaming", Boolean::class.javaPrimitiveType)
                            .invoke(ni, roaming)
                    }
            }
            shadowOf(connectivity).setActiveNetworkInfo(info)
        }

        fun plugged(value: Int) {
            context.sendStickyBroadcast(
                Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, value)
            )
        }

        fun powerSave(on: Boolean) = shadowOf(power).setIsPowerSaveMode(on)

        fun masterSync(on: Boolean) = ContentResolver.setMasterSyncAutomatically(on)

        /**
         * [value] is the raw network name; WifiInfo.getSSID() adds the quotes. Null leaves it
         * unknown.
         */
        fun ssid(value: String?) {
            val info = ShadowWifiInfo.newInstance()
            if (value != null) shadowOf(info).setSSID(value)
            shadowOf(wifi).setConnectionInfo(info)
        }

        fun pref(key: String, value: Any) {
            prefs
                .edit()
                .apply {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
                        else -> error("Unsupported pref type for $key")
                    }
                }
                .commit()
        }
    }

    private val wifiType = ConnectivityManager.TYPE_WIFI
    private val mobileType = ConnectivityManager.TYPE_MOBILE

    private val scenarios =
        listOf(
            Scenario("defaults, on wi-fi") { network(wifiType) },
            Scenario("defaults, on ethernet") { network(ConnectivityManager.TYPE_ETHERNET) },
            Scenario("defaults, on mobile data") { network(mobileType) },
            Scenario("defaults, no network (flight mode)") { network(null) },
            Scenario("mobile data allowed, on mobile data") {
                network(mobileType)
                pref(Constants.PREF_RUN_ON_MOBILE_DATA, true)
            },
            Scenario("mobile data allowed, roaming") {
                network(mobileType, roaming = true)
                pref(Constants.PREF_RUN_ON_MOBILE_DATA, true)
            },
            Scenario("mobile data and roaming allowed, roaming") {
                network(mobileType, roaming = true)
                pref(Constants.PREF_RUN_ON_MOBILE_DATA, true)
                pref(Constants.PREF_RUN_ON_ROAMING, true)
            },
            Scenario("wi-fi disallowed, on wi-fi") {
                network(wifiType)
                pref(Constants.PREF_RUN_ON_WIFI, false)
            },
            Scenario("metered wi-fi allowed, on wi-fi") {
                network(wifiType)
                pref(Constants.PREF_RUN_ON_METERED_WIFI, true)
            },
            Scenario("flight mode allowed, no network") {
                network(null)
                pref(Constants.PREF_RUN_IN_FLIGHT_MODE, true)
            },
            Scenario("whitelist on, SSID listed") {
                network(wifiType)
                ssid("home")
                pref(Constants.PREF_USE_WIFI_SSID_WHITELIST, true)
                pref(Constants.PREF_WIFI_SSID_WHITELIST, setOf("\"home\""))
            },
            Scenario("whitelist on, SSID not listed") {
                network(wifiType)
                ssid("cafe")
                pref(Constants.PREF_USE_WIFI_SSID_WHITELIST, true)
                pref(Constants.PREF_WIFI_SSID_WHITELIST, setOf("\"home\""))
            },
            Scenario("whitelist on, SSID unknown (no location)") {
                network(wifiType)
                ssid(null)
                pref(Constants.PREF_USE_WIFI_SSID_WHITELIST, true)
                pref(Constants.PREF_WIFI_SSID_WHITELIST, setOf("\"home\""))
            },
            Scenario("whitelist on, no wi-fi info") {
                network(wifiType)
                pref(Constants.PREF_USE_WIFI_SSID_WHITELIST, true)
                pref(Constants.PREF_WIFI_SSID_WHITELIST, setOf("\"home\""))
            },
            Scenario("charger only, on battery") {
                network(wifiType)
                plugged(0)
                pref(Constants.PREF_POWER_SOURCE, Constants.PowerSource.CHARGER)
            },
            Scenario("charger only, on AC") {
                network(wifiType)
                plugged(BatteryManager.BATTERY_PLUGGED_AC)
                pref(Constants.PREF_POWER_SOURCE, Constants.PowerSource.CHARGER)
            },
            Scenario("charger only, docked") {
                network(wifiType)
                plugged(BatteryManager.BATTERY_PLUGGED_DOCK)
                pref(Constants.PREF_POWER_SOURCE, Constants.PowerSource.CHARGER)
            },
            Scenario("battery only, on USB") {
                network(wifiType)
                plugged(BatteryManager.BATTERY_PLUGGED_USB)
                pref(Constants.PREF_POWER_SOURCE, Constants.PowerSource.BATTERY)
            },
            Scenario("battery saver on, respected (default)") {
                network(wifiType)
                powerSave(true)
            },
            Scenario("battery saver on, ignored") {
                network(wifiType)
                powerSave(true)
                pref(Constants.PREF_RESPECT_BATTERY_SAVING, false)
            },
            Scenario("auto-sync off, respected") {
                network(wifiType)
                masterSync(false)
                pref(Constants.PREF_RESPECT_MASTER_SYNC, true)
            },
            Scenario("auto-sync off, ignored (default)") {
                network(wifiType)
                masterSync(false)
            },
            Scenario("force start, no network") {
                network(null)
                pref(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_START)
            },
            Scenario("force stop, on wi-fi") {
                network(wifiType)
                pref(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_STOP)
            },
            Scenario("time schedule on, never ran") {
                network(wifiType)
                pref(Constants.PREF_RUN_ON_TIME_SCHEDULE, true)
            },
        )

    private fun reset() {
        prefs.edit().clear().commit()
        shadowOf(connectivity).setActiveNetworkInfo(null)
        context.removeStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        shadowOf(power).setIsPowerSaveMode(false)
        ContentResolver.setMasterSyncAutomatically(true)
        shadowOf(wifi).setConnectionInfo(null)
    }

    private fun run(scenario: Scenario): String {
        reset()
        Device().apply(scenario.setup)
        val decisions = mutableListOf<Boolean>()
        var preconditionCallbacks = 0
        val monitor = RunConditionMonitor(context, { decisions += it }, { preconditionCallbacks++ })
        val explanation = monitor.runDecisionExplanation.replace("\n", " | ")
        monitor.shutdown()
        return "## ${scenario.name}\n" +
            "shouldRun callbacks: $decisions\n" +
            "precondition callbacks: $preconditionCallbacks\n" +
            "explanation: $explanation\n"
    }

    @Test
    fun `should-run decision matrix`() {
        Golden.assertMatches(
            "runconditions/decisions.txt",
            scenarios.joinToString("\n") { run(it) },
        )
    }

    @Test
    fun `per-object sync conditions`() {
        val objectId = "folder_cam-photos"
        val cases =
            listOf<Pair<String, Device.() -> Unit>>(
                "object defaults, on wi-fi" to { network(wifiType) },
                "object defaults, on mobile data" to { network(mobileType) },
                "object mobile data allowed, on mobile data" to
                    {
                        network(mobileType)
                        pref(Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectId), true)
                    },
                "object wi-fi disallowed, on wi-fi" to
                    {
                        network(wifiType)
                        pref(Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(objectId), false)
                    },
                "object charger only, on battery" to
                    {
                        network(wifiType)
                        plugged(0)
                        pref(
                            Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectId),
                            Constants.PowerSource.CHARGER,
                        )
                    },
            )
        val report =
            cases.joinToString("\n") { (name, setup) ->
                reset()
                Device().apply(setup)
                val monitor = RunConditionMonitor(context, {}, {})
                val result = monitor.checkObjectSyncConditions(objectId)
                monitor.shutdown()
                "$name -> $result"
            }
        Golden.assertMatches("runconditions/object-conditions.txt", report)
    }
}
