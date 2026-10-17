package com.nutomic.syncthingandroid.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 6.dp

/**
 * The shape of item [index] of [count] in a grouped list: rounded where the group starts and ends,
 * tight between items.
 */
fun groupedItemShape(index: Int, count: Int): RoundedCornerShape =
    RoundedCornerShape(
        topCorner(index, emphasized = false),
        topCorner(index, emphasized = false),
        bottomCorner(index, count, emphasized = false),
        bottomCorner(index, count, emphasized = false),
    )

/**
 * [groupedItemShape] for an item that morphs to fully rounded while [emphasized] (pressed or
 * selected), with the theme's spatial motion.
 */
@Composable
fun morphingItemShape(index: Int, count: Int, emphasized: Boolean): RoundedCornerShape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(topCorner(index, emphasized), spec, label = "top corners")
    val bottom by
        animateDpAsState(bottomCorner(index, count, emphasized), spec, label = "bottom corners")
    return RoundedCornerShape(top, top, bottom, bottom)
}

private fun topCorner(index: Int, emphasized: Boolean): Dp =
    if (emphasized || index == 0) OUTER_CORNER else INNER_CORNER

private fun bottomCorner(index: Int, count: Int, emphasized: Boolean): Dp =
    if (emphasized || index == count - 1) OUTER_CORNER else INNER_CORNER

/** The title above a group of items. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
    )
}

/**
 * Sync progress as a wavy line; [progress] is 0 to 1. The wave moves only while the system allows
 * animations ([rememberAnimationsEnabled]).
 */
@Composable
fun SyncProgress(progress: Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(
        progress = { progress },
        waveSpeed =
            if (rememberAnimationsEnabled())
                WavyProgressIndicatorDefaults.LinearDeterminateWavelength
            else 0.dp,
        modifier =
            modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
    )
}
