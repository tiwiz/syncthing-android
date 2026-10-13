package com.nutomic.syncthingandroid.service

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The preference keys built per folder or device (they end up in users' backups, so they must not
 * change), the app's file locations and the platform checks.
 */
@RunWith(AndroidJUnit4::class)
class ConstantsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedOsVersion: String? = System.getProperty("os.version")

    @After
    fun tearDown() {
        System.setProperty("os.version", savedOsVersion.orEmpty())
    }

    @Test
    fun `per object preference keys`() {
        val prefix = "sc_folder_cam-photos"

        assertEquals(
            "sc_folder_cam-photos_custom_sync_conditions",
            Constants.dynPrefObjectCustomSyncConditions(prefix),
        )
        assertEquals(
            "sc_folder_cam-photos_run_script",
            Constants.dynPrefObjectFolderRunScript("cam-photos"),
        )
        assertEquals("sc_folder_cam-photos_run_on_wifi", Constants.dynPrefObjectSyncOnWifi(prefix))
        assertEquals(
            "sc_folder_cam-photos_use_wifi_whitelist",
            Constants.dynPrefObjectUseWifiSsidWhitelist(prefix),
        )
        assertEquals(
            "sc_folder_cam-photos_wifi_ssid_whitelist",
            Constants.dynPrefObjectSelectedWhitelistSsid(prefix),
        )
        assertEquals(
            "sc_folder_cam-photos_run_on_metered_wifi",
            Constants.dynPrefObjectSyncOnMeteredWifi(prefix),
        )
        assertEquals(
            "sc_folder_cam-photos_run_on_mobile_data",
            Constants.dynPrefObjectSyncOnMobileData(prefix),
        )
        assertEquals(
            "sc_folder_cam-photos_power_source",
            Constants.dynPrefObjectSyncOnPowerSource(prefix),
        )
    }

    @Test
    fun `files live in the app's private folders and the native library folder`() {
        val files = context.filesDir
        assertEquals(File(files, "config.xml"), Constants.getConfigFile(context))
        assertEquals(File(files, "config.xml.tmp"), Constants.getConfigTempFile(context))
        assertEquals(File(files, "cert.pem"), Constants.getPublicKeyFile(context))
        assertEquals(File(files, "key.pem"), Constants.getPrivateKeyFile(context))
        assertEquals(File(files, "index-v2"), Constants.getIndexDbFolder(context))
        assertEquals(File(files, "https-cert.pem"), Constants.getHttpsCertFile(context))
        assertEquals(File(files, "https-key.pem"), Constants.getHttpsKeyFile(context))
        assertEquals(File(files, "sharedpreferences.dat"), Constants.getSharedPrefsFile(context))
        assertEquals(File(files, "syncthing.log"), Constants.getSyncthingLogFile(context))
        assertEquals(File(context.cacheDir, "android.log"), Constants.getAndroidLogFile(context))
        assertEquals(
            File(context.applicationInfo.nativeLibraryDir, "libsyncthingnative.so"),
            Constants.getSyncthingBinary(context),
        )
    }

    @Test
    fun `the debug build is debuggable`() {
        assertTrue(Constants.isDebuggable(context))
    }

    @Test
    fun `TLS 1_2 is used except on Android 7_0`() {
        assertTrue(Constants.osSupportsTLS12())
    }

    @Test
    @Config(sdk = [24])
    fun `Android 7_0 falls back to plain http`() {
        assertFalse(Constants.osSupportsTLS12())
    }

    @Test
    fun `kernels 3_0 and 3_4 have the NAT bug`() {
        System.setProperty("os.version", "3.0.101-gf32669ee5be")
        assertTrue(Constants.osHasKernelBugIssue505())
        System.setProperty("os.version", "3.4.0")
        assertTrue(Constants.osHasKernelBugIssue505())
        System.setProperty("os.version", "6.1.0-android")
        assertFalse(Constants.osHasKernelBugIssue505())
    }

    @Test
    fun `the emulator is recognised by manufacturer and model`() {
        assertFalse(Constants.isRunningOnEmulator())

        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Android SDK built for x86_64")

        assertTrue(Constants.isRunningOnEmulator())
    }

    @Test
    fun `defaults`() {
        assertEquals(8384, Constants.DEFAULT_WEBGUI_TCP_PORT.toInt())
        assertEquals(22000, Constants.DEFAULT_DATA_TCP_PORT.toInt())
        assertEquals(3000L, Constants.REST_UPDATE_INTERVAL)
        assertEquals(1000L, Constants.GUI_UPDATE_INTERVAL)
        assertFalse(Constants.ENABLE_TEST_DATA)
    }
}
