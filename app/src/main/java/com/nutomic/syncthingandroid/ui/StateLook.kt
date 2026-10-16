package com.nutomic.syncthingandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.views.StateTone

/** The icon sits inside the badge with a quarter of its size free on each side. */
private const val ICON_INSET_DIVISOR = 4

/** The expressive shapes a state badge can take. */
enum class StateShape {
    COOKIE,
    FLOWER,
    SUNNY,
    CIRCLE,
}

/** Which container color pair a state badge uses. */
enum class StateColors {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    ERROR,
    NEUTRAL,
}

/** How a [StateTone] looks: never color alone, the shape and icon tell tones apart too. */
data class StateLook(val shape: StateShape, val colors: StateColors, val icon: ImageVector)

fun StateTone.look(): StateLook =
    when (this) {
        StateTone.OK -> StateLook(StateShape.FLOWER, StateColors.TERTIARY, Icons.Rounded.Check)
        StateTone.BUSY -> StateLook(StateShape.COOKIE, StateColors.PRIMARY, Icons.Rounded.Sync)
        StateTone.ATTENTION ->
            StateLook(StateShape.SUNNY, StateColors.SECONDARY, Icons.Rounded.HourglassEmpty)
        StateTone.PAUSED -> StateLook(StateShape.SUNNY, StateColors.SECONDARY, Icons.Rounded.Pause)
        StateTone.ERROR ->
            StateLook(StateShape.COOKIE, StateColors.ERROR, Icons.Rounded.PriorityHigh)
        StateTone.OFFLINE ->
            StateLook(StateShape.CIRCLE, StateColors.NEUTRAL, Icons.Rounded.CloudOff)
    }

@Composable
private fun StateShape.toComposeShape(): Shape =
    when (this) {
        StateShape.COOKIE -> MaterialShapes.Cookie9Sided.toShape()
        StateShape.FLOWER -> MaterialShapes.Flower.toShape()
        StateShape.SUNNY -> MaterialShapes.Sunny.toShape()
        StateShape.CIRCLE -> MaterialShapes.Circle.toShape()
    }

private val StateColors.container: Color
    @Composable
    @ReadOnlyComposable
    get() =
        when (this) {
            StateColors.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
            StateColors.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer
            StateColors.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer
            StateColors.ERROR -> MaterialTheme.colorScheme.errorContainer
            StateColors.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
        }

private val StateColors.content: Color
    @Composable
    @ReadOnlyComposable
    get() =
        when (this) {
            StateColors.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
            StateColors.SECONDARY -> MaterialTheme.colorScheme.onSecondaryContainer
            StateColors.TERTIARY -> MaterialTheme.colorScheme.onTertiaryContainer
            StateColors.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            StateColors.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        }

/** A [tone]'s shape with its icon; decorative, the row's text says the state. */
@Composable
fun StateBadge(tone: StateTone, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val look = tone.look()
    Box(
        modifier =
            modifier.size(size).background(look.colors.container, look.shape.toComposeShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = look.icon,
            contentDescription = null,
            tint = look.colors.content,
            modifier = Modifier.padding(size / ICON_INSET_DIVISOR),
        )
    }
}
