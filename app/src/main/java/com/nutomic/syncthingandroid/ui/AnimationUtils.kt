package com.nutomic.syncthingandroid.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the system currently allows animations. Reads `Settings.Global.ANIMATOR_DURATION_SCALE`
 * once per composition; the value rarely changes while the app is in the foreground, and reading it
 * repeatedly would add noise without benefit.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
