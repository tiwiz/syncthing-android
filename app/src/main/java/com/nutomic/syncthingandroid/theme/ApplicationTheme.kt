package com.nutomic.syncthingandroid.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.nutomic.syncthingandroid.ui.isSpatialUi

private val SpatialTypography by lazy { AppTypography.forSpatial() }

/**
 * Material 3 Expressive: dynamic color from Android 12, the Syncthing blue scheme before; Roboto
 * Flex type and expressive motion.
 */
@Composable
fun ApplicationTheme(spatial: Boolean = isSpatialUi, content: @Composable () -> Unit) {
    val isDarkTheme = isSystemInDarkTheme()
    val colorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDarkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        } else {
            if (isDarkTheme) SyncthingDarkColors else SyncthingLightColors
        }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = if (spatial) SpatialTypography else AppTypography,
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides
                if (spatial) SPATIAL_MIN_TARGET else LocalMinimumInteractiveComponentSize.current,
            content = content,
        )
    }
}
