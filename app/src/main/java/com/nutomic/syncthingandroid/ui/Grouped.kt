package com.nutomic.syncthingandroid.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun groupedItemShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) OUTER_CORNER else INNER_CORNER
    val bottom = if (index == count - 1) OUTER_CORNER else INNER_CORNER
    return RoundedCornerShape(top, top, bottom, bottom)
}

/**
 * The shape of a selected item that stands alone in the list, e.g. the row open in the detail pane.
 */
fun selectedGroupedItemShape(): RoundedCornerShape = RoundedCornerShape(OUTER_CORNER)

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
 * Sync progress as a wavy line; [progress] is 0 to 1.
 *
 * The wave is still by default so screenshots and tests stay idle; callers that know animations are
 * enabled can pass [LinearWavyProgressIndicatorDefaults.waveSpeed] or another non-zero speed.
 */
@Composable
fun SyncProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    waveSpeed: Dp = 0.dp,
) {
    LinearWavyProgressIndicator(
        progress = { progress },
        waveSpeed = waveSpeed,
        modifier =
            modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
    )
}
