package com.nutomic.syncthingandroid.settings

import com.nutomic.syncthingandroid.util.runShellCommandGetOutput
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.Preference


private const val TAG = "SettingsAboutScreen"

fun EntryProviderScope<SettingsRoute>.settingsAboutEntry(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
) {
    entry<SettingsRoute.About> {
        SettingsAboutScreen(navigator = navigator, serviceState = serviceState)
    }
}


@Composable
fun SettingsAboutScreen(
    navigator: Navigator<SettingsRoute>,
    serviceState: SyncthingServiceState,
    modifier: Modifier = Modifier,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val stService = serviceState.service
    val stServiceTick = serviceState.updateTick

    val loading = stringResource(R.string.state_loading)
    val unknown = stringResource(R.string.state_unknown)

    val state by produceState(initialValue = AboutState(
            appVersion = loading,
            coreVersion = loading,
            dbSize = loading,
            fileLimit = loading,
        ), stService, stServiceTick) {
        value = withContext(ioDispatcher) {
            AboutState(
                appVersion = getAppVersion(context) ?: unknown,
                coreVersion = stService?.api?.version ?: unknown,
                dbSize = getDatabaseSize(context) ?: unknown,
                fileLimit = getOpenFileLimit() ?: unknown,
            )
        }
    }

    SettingsScaffold(
        onNavigateBack = { navigator.navigateBack() },
        modifier = modifier,
        title = stringResource(R.string.category_about),
    ) {
        item {
            Preference(
                title = { Text(stringResource(R.string.app_version_title)) },
                summary = { Text(state.appVersion) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.syncthing_version_title)) },
                summary = { Text(state.coreVersion) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.syncthing_database_size)) },
                summary = { Text(state.dbSize) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.os_open_file_limit)) },
                summary = { Text(state.fileLimit) },
            )
        }
        item {
            val stForumUri = stringResource(R.string.syncthing_forum_url)
            Preference(
                title = { Text(stringResource(R.string.syncthing_forum_title)) },
                summary = { Text(stringResource(R.string.syncthing_forum_summary)) },
                onClick = { uriHandler.openUri(stForumUri) },
            )
        }
        item {
            val stPrivacyPolicyUri = stringResource(R.string.privacy_policy_url)
            Preference(
                title = { Text(stringResource(R.string.privacy_title)) },
                summary = { Text(stringResource(R.string.privacy_summary)) },
                onClick = { uriHandler.openUri(stPrivacyPolicyUri) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.open_source_licenses_title)) },
                summary = { Text(stringResource(R.string.open_source_licenses_summary)) },
                onClick = { navigator.navigateTo(SettingsRoute.Licenses) },
            )
        }
    }
}

data class AboutState(
    val appVersion: String = "",
    val coreVersion: String = "",
    val dbSize: String = "",
    val fileLimit: String = ""
)

private fun getAppVersion(context: Context): String? {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${packageInfo.versionName}"
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(TAG, "Failed to get app version name")
        null
    }
}

private fun getOpenFileLimit(): String? {
    val result = runShellCommandGetOutput("ulimit -n")
    return if (result.isBlank()) null else result.trim()
}

private fun getDatabaseSize(context: Context): String? {
    val dbPath = Constants.getIndexDbFolder(context).absolutePath
    val result = runShellCommandGetOutput("/system/bin/du -sh $dbPath")

    if (result.isBlank()) {
        return null
    }

    // Split by whitespace and grab the first part (the size)
    val resultParts = result.trim().split(Regex("\\s+"))
    return resultParts.firstOrNull()
}
