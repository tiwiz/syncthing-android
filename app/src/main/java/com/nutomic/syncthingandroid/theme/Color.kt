package com.nutomic.syncthingandroid.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The fallback schemes, used without dynamic color (below Android 12). Seed: Syncthing blue
// #1F8FD6; teal tertiary for "up to date". The same values as the design canvas
// (docs/plans/M3-EXPRESSIVE.md).

internal val SyncthingLightColors =
    lightColorScheme(
        primary = Color(0xFF00639B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCEE5FF),
        onPrimaryContainer = Color(0xFF001D33),
        inversePrimary = Color(0xFF96CCFF),
        secondary = Color(0xFF51606F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD5E4F7),
        onSecondaryContainer = Color(0xFF0E1D2A),
        tertiary = Color(0xFF006B5C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF9EF2DE),
        onTertiaryContainer = Color(0xFF00201B),
        background = Color(0xFFF8F9FF),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF8F9FF),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFDEE3EB),
        onSurfaceVariant = Color(0xFF42474E),
        surfaceTint = Color(0xFF00639B),
        inverseSurface = Color(0xFF2E3135),
        inverseOnSurface = Color(0xFFEFF0F7),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF72777F),
        outlineVariant = Color(0xFFC2C7CF),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF8F9FF),
        surfaceContainer = Color(0xFFECEEF4),
        surfaceContainerHigh = Color(0xFFE6E8EE),
        surfaceContainerHighest = Color(0xFFE1E2E8),
        surfaceContainerLow = Color(0xFFF2F3FA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD8DAE0),
    )

internal val SyncthingDarkColors =
    darkColorScheme(
        primary = Color(0xFF96CCFF),
        onPrimary = Color(0xFF003353),
        primaryContainer = Color(0xFF004A77),
        onPrimaryContainer = Color(0xFFCEE5FF),
        inversePrimary = Color(0xFF00639B),
        secondary = Color(0xFFB9C8DA),
        onSecondary = Color(0xFF233240),
        secondaryContainer = Color(0xFF3A4857),
        onSecondaryContainer = Color(0xFFD5E4F7),
        tertiary = Color(0xFF82D5C3),
        onTertiary = Color(0xFF00382F),
        tertiaryContainer = Color(0xFF005144),
        onTertiaryContainer = Color(0xFF9EF2DE),
        background = Color(0xFF111418),
        onBackground = Color(0xFFE1E2E8),
        surface = Color(0xFF111418),
        onSurface = Color(0xFFE1E2E8),
        surfaceVariant = Color(0xFF42474E),
        onSurfaceVariant = Color(0xFFC2C7CF),
        surfaceTint = Color(0xFF96CCFF),
        inverseSurface = Color(0xFFE1E2E8),
        inverseOnSurface = Color(0xFF2E3135),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8C9199),
        outlineVariant = Color(0xFF42474E),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF37393E),
        surfaceContainer = Color(0xFF1D2024),
        surfaceContainerHigh = Color(0xFF272A2F),
        surfaceContainerHighest = Color(0xFF32353A),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainerLowest = Color(0xFF0C0E12),
        surfaceDim = Color(0xFF111418),
    )
