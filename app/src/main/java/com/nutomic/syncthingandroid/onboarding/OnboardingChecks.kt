package com.nutomic.syncthingandroid.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingRunnable.ExecutableNotFoundException
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.ConfigXml.OpenConfigException
import com.nutomic.syncthingandroid.util.PermissionUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// What OnboardingActivity checks to decide which pages to show, and the key generation it runs.

private const val TAG = "OnboardingActivity"

internal fun Context.haveStoragePermission(): Boolean {
    return PermissionUtil.haveStoragePermission(this)
}

internal fun Context.haveIgnoreDozePermission(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

internal fun Context.haveLocationPermission(): Boolean {
    val coarseLocationGranted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val backgroundLocationGranted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    return coarseLocationGranted && backgroundLocationGranted
}

internal fun Context.haveLocalNetworkPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
        return true
    }
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED
}

internal fun Context.haveNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
    }
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

internal fun Context.checkForParseableConfig(): Boolean {
    val configExists = Constants.getConfigFile(this).exists()
    if (!configExists) {
        return false
    }

    try {
        val configParseTest = ConfigXml(this)
        configParseTest.loadConfig()
        return true
    } catch (_: OpenConfigException) {
        Log.d(TAG, "Failed to parse existing config. Will show key generation slide ...")
    }

    return false
}

/** Generates the keys and config.xml; returns the error to show, or null on success. */
internal suspend fun Context.generateConfig(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
): String? =
    withContext(ioDispatcher) {
        try {
            ConfigXml(this@generateConfig).generateConfig()
            null
        } catch (e: ExecutableNotFoundException) {
            getString(R.string.executable_not_found, e.message)
        } catch (_: OpenConfigException) {
            getString(R.string.config_create_failed)
        }
    }
