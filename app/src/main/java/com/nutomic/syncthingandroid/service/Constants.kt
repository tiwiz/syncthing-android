package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.text.TextUtils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Preference keys, file locations and platform checks. Preference keys are stored on the device and
 * in backups: never change their values.
 */
// Constants and the file locations that belong with them.
@Suppress("TooManyFunctions")
object Constants {

    // Always set ENABLE_TEST_DATA to false before building debug or release APK's.
    const val ENABLE_TEST_DATA: Boolean = false

    const val FILENAME_SYNCTHING_BINARY = "libsyncthingnative.so"
    const val FILENAME_STIGNORE = ".stignore"
    const val FILENAME_STFOLDER = ".stfolder"
    const val FOLDER_NAME_STVERSIONS = ".stversions"

    // Preferences - Run conditions
    const val PREF_RUN_ON_MOBILE_DATA = "run_on_mobile_data"
    const val PREF_RUN_ON_ROAMING = "run_on_roaming"
    const val PREF_RUN_ON_WIFI = "run_on_wifi"
    const val PREF_RUN_ON_METERED_WIFI = "run_on_metered_wifi"
    const val PREF_USE_WIFI_SSID_WHITELIST = "use_wifi_whitelist"
    const val PREF_WIFI_SSID_WHITELIST = "wifi_ssid_whitelist"
    const val PREF_POWER_SOURCE = "power_source"

    object PowerSource {
        const val CHARGER_BATTERY = "ac_and_battery_power"
        const val CHARGER = "ac_power"
        const val BATTERY = "battery_power"
    }

    const val PREF_RESPECT_BATTERY_SAVING = "respect_battery_saving"
    const val PREF_RESPECT_MASTER_SYNC = "respect_master_sync"
    const val PREF_RUN_IN_FLIGHT_MODE = "run_in_flight_mode"
    const val PREF_RUN_ON_TIME_SCHEDULE = "run_on_time_schedule"
    const val PREF_SYNC_DURATION_MINUTES = "sync_duration_minutes"
    const val PREF_SLEEP_INTERVAL_MINUTES = "sleep_interval_minutes"

    // Preferences - User Interface
    const val PREF_APP_THEME = "app_theme"
    const val PREF_EXPERT_MODE = "expert_mode"
    const val PREF_START_INTO_WEB_GUI = "start_into_web_gui"

    // Preferences - Behaviour
    const val PREF_START_SERVICE_ON_BOOT = "always_run_in_background"
    const val PREF_BROADCAST_SERVICE_CONTROL = "broadcast_service_control"
    const val PREF_ALLOW_OVERWRITE_FILES = "allow_overwrite_files"

    // Preferences - Syncthing Options
    const val PREF_WEBUI_USERNAME = "webui_username"
    const val PREF_WEBUI_PASSWORD = "webui_password"

    // Preferences - Import and Export
    const val PREF_BACKUP_REL_PATH_TO_ZIP = "backup_rel_path_to_zip"
    const val PREF_BACKUP_PASSWORD = "backup_password"

    // Preferences - Troubleshooting
    const val PREF_VERBOSE_LOG = "verbose_log"
    const val PREF_ENVIRONMENT_VARIABLES = "environment_variables"
    const val PREF_DEBUG_FACILITIES_ENABLED = "debug_facilities_enabled"

    // Preferences - Experimental
    const val PREF_USE_TOR = "use_tor"
    const val PREF_SOCKS_PROXY_ADDRESS = "socks_proxy_address"
    const val PREF_HTTP_PROXY_ADDRESS = "http_proxy_address"

    // Preferences - per Folder and Device Sync Conditions
    const val PREF_OBJECT_PREFIX_FOLDER = "sc_folder_"
    const val PREF_OBJECT_PREFIX_DEVICE = "sc_device_"

    // Preferences: Recent Changes screen
    const val PREF_SHOW_EXACT_TIMES = "recent_changes_show_exact_times"

    @JvmStatic
    fun dynPrefObjectCustomSyncConditions(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + "custom_sync_conditions"

    @JvmStatic
    fun dynPrefObjectFolderRunScript(folderId: String): String =
        PREF_OBJECT_PREFIX_FOLDER + folderId + "_" + "run_script"

    @JvmStatic
    fun dynPrefObjectSyncOnWifi(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + PREF_RUN_ON_WIFI

    @JvmStatic
    fun dynPrefObjectUseWifiSsidWhitelist(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + PREF_USE_WIFI_SSID_WHITELIST

    @JvmStatic
    fun dynPrefObjectSelectedWhitelistSsid(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + PREF_WIFI_SSID_WHITELIST

    @JvmStatic
    fun dynPrefObjectSyncOnMeteredWifi(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + PREF_RUN_ON_METERED_WIFI

    @JvmStatic
    fun dynPrefObjectSyncOnMobileData(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + PREF_RUN_ON_MOBILE_DATA

    @JvmStatic
    fun dynPrefObjectSyncOnPowerSource(objectPrefixAndId: String): String =
        objectPrefixAndId + "_" + PREF_POWER_SOURCE

    // Cached information which is not available on SettingsActivity.
    const val PREF_ENABLE_SYNCTHING_CAMERA = "enableSyncthingCamera"
    const val PREF_KNOWN_WIFI_SSIDS = "knownWifiSsids"
    const val PREF_LAST_BINARY_VERSION = "lastBinaryVersion"
    const val PREF_LOCAL_DEVICE_ID = "localDeviceID"
    // from SystemClock.elapsedRealtime()
    const val PREF_LAST_RUN_TIME = "last_run_time"
    const val PREF_APP_START_COUNTER = "app_start_counter"

    /** Cached device stats. */
    const val PREF_CACHE_DEVICE_LASTSEEN_PREFIX = "device_lastseen_"

    /** The "Syncthing Camera" folder ConfigXml adds. */
    const val SYNCTHING_CAMERA_FOLDER_ID = "syncthingAndroidCamera-52x89-60es4"

    /** RunConditionMonitor, StatusFragment */
    const val PREF_BTNSTATE_FORCE_START_STOP = "btnStateForceStartStop"

    const val BTNSTATE_NO_FORCE_START_STOP = 0
    const val BTNSTATE_FORCE_START = 1
    const val BTNSTATE_FORCE_STOP = 2

    /** EventProcessor */
    const val PREF_EVENT_PROCESSOR_LAST_SYNC_ID = "last_sync_id"

    /**
     * Available options cache for preference "debug_facilities_enabled", read through the REST API
     * in RestApi.updateDebugFacilitiesCache after the first successful binary startup.
     */
    const val PREF_DEBUG_FACILITIES_AVAILABLE = "debug_facilities_available"

    // Available folder types.
    const val FOLDER_TYPE_SEND_ONLY = "sendonly"
    const val FOLDER_TYPE_SEND_RECEIVE = "sendreceive"
    const val FOLDER_TYPE_RECEIVE_ONLY = "receiveonly"
    const val FOLDER_TYPE_RECEIVE_ENCRYPTED = "receiveencrypted"

    // Default listening ports.
    const val DEFAULT_WEBGUI_TCP_PORT = 8384
    const val DEFAULT_DATA_TCP_PORT = 22000

    /**
     * Interval in ms at which RestAPI is polled. As a rule of thumb: Poll faster on "modern"
     * devices.
     */
    @JvmField val REST_UPDATE_INTERVAL: Long = TimeUnit.SECONDS.toMillis(3L)

    @JvmField val GUI_UPDATE_INTERVAL: Long = TimeUnit.SECONDS.toMillis(1)

    /** File in the config folder that contains configuration. */
    const val CONFIG_FILE = "config.xml"

    @JvmStatic fun getConfigFile(context: Context): File = File(context.filesDir, CONFIG_FILE)

    /** File in the config folder we write to temporarily before renaming to CONFIG_FILE. */
    private const val CONFIG_TEMP_FILE = "config.xml.tmp"

    @JvmStatic
    fun getConfigTempFile(context: Context): File = File(context.filesDir, CONFIG_TEMP_FILE)

    /** Name of the public key file in the data directory. */
    const val PUBLIC_KEY_FILE = "cert.pem"

    @JvmStatic
    fun getPublicKeyFile(context: Context): File = File(context.filesDir, PUBLIC_KEY_FILE)

    /** Name of the private key file in the data directory. */
    const val PRIVATE_KEY_FILE = "key.pem"

    @JvmStatic
    fun getPrivateKeyFile(context: Context): File = File(context.filesDir, PRIVATE_KEY_FILE)

    /** Name of the folder containing the index database. */
    private const val INDEX_DB_FOLDER = "index-v2"

    @JvmStatic
    fun getIndexDbFolder(context: Context): File = File(context.filesDir, INDEX_DB_FOLDER)

    /** Name of the public HTTPS CA file in the data directory. */
    const val HTTPS_CERT_FILE = "https-cert.pem"

    @JvmStatic
    fun getHttpsCertFile(context: Context): File = File(context.filesDir, HTTPS_CERT_FILE)

    /** Name of the HTTPS CA key file in the data directory. */
    const val HTTPS_KEY_FILE = "https-key.pem"

    @JvmStatic fun getHttpsKeyFile(context: Context): File = File(context.filesDir, HTTPS_KEY_FILE)

    /**
     * Name of the file holding the SharedPreferences backup. Not in getCacheDir(): the path to
     * import would then be wrong, as zipFile.extractAll writes to getFilesDir().
     */
    const val SHARED_PREFS_FILE = "sharedpreferences.dat"

    @JvmStatic
    fun getSharedPrefsFile(context: Context): File = File(context.filesDir, SHARED_PREFS_FILE)

    /** libsyncthingnative.so's absolute path and filename. */
    @JvmStatic
    fun getSyncthingBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, FILENAME_SYNCTHING_BINARY)

    /** e.g. /data/data/${applicationId}/cache/android.log */
    @JvmStatic fun getAndroidLogFile(context: Context): File = File(context.cacheDir, "android.log")

    /** e.g. /data/data/${applicationId}/files/syncthing.log */
    @JvmStatic
    fun getSyncthingLogFile(context: Context): File = File(context.filesDir, "syncthing.log")

    /** Whether the app is running on an Android emulator (AVD). */
    @JvmStatic
    fun isRunningOnEmulator(): Boolean =
        !TextUtils.isEmpty(Build.MANUFACTURER) &&
            !TextUtils.isEmpty(Build.MODEL) &&
            (Build.MANUFACTURER == "Google" || Build.MANUFACTURER == "unknown") &&
            Build.MODEL in
                setOf(
                    "Android SDK built for x86",
                    "Android SDK built for x86_64",
                    "sdk_gphone_x86_arm",
                )

    @JvmStatic
    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Detects kernels with a bug causing kernel oops when Syncthing v1.3.0+ attempts to enable the
     * NAT feature. Affected kernels: Samsung Note N7000 - LOS 16 - Android 9 - 3.0.101-gf32669ee5be
     */
    @JvmStatic
    fun osHasKernelBugIssue505(): Boolean {
        val kernelVersion = System.getProperty("os.version") ?: return false
        return kernelVersion.startsWith("3.0.") || kernelVersion.startsWith("3.4.")
    }
}
