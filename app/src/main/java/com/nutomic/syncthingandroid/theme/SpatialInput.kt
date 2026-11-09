package com.nutomic.syncthingandroid.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutomic.syncthingandroid.ui.isSpatialUi

/** A hand ray is less precise than a finger, and text is further away. */
internal val SPATIAL_MIN_TARGET = 56.dp
private val SPATIAL_MIN_TEXT = 14.sp
private val SPATIAL_MIN_LINE = 20.sp
private val FOCUS_RING_WIDTH = 3.dp

private fun TextStyle.legible(): TextStyle =
    copy(
        fontSize = if (fontSize < SPATIAL_MIN_TEXT) SPATIAL_MIN_TEXT else fontSize,
        lineHeight = if (lineHeight < SPATIAL_MIN_LINE) SPATIAL_MIN_LINE else lineHeight,
    )

/** The type scale for Full Space: the small styles go up to a size readable at panel distance. */
internal fun Typography.forSpatial(): Typography =
    copy(
        bodySmall = bodySmall.legible(),
        labelMedium = labelMedium.legible(),
        labelSmall = labelSmall.legible(),
        bodySmallEmphasized = bodySmallEmphasized.legible(),
        labelMediumEmphasized = labelMediumEmphasized.legible(),
        labelSmallEmphasized = labelSmallEmphasized.legible(),
    )

/** A ring around a focused item in Full Space, for keyboards and controllers; else nothing. */
@Composable
fun focusRing(
    interactionSource: InteractionSource,
    shape: Shape,
    color: Color,
    spatial: Boolean = isSpatialUi,
): Modifier {
    if (!spatial) return Modifier
    val focused by interactionSource.collectIsFocusedAsState()
    return if (focused) Modifier.border(FOCUS_RING_WIDTH, color, shape) else Modifier
}
