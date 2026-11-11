package com.nutomic.syncthingandroid.activities

import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether a detail opens next to its list. In Full Space each has its own panel. */
class MainPanesTest {

    private fun directive(partitions: Int) =
        PaneScaffoldDirective(
            maxHorizontalPartitions = partitions,
            horizontalPartitionSpacerSize = 0.dp,
            maxVerticalPartitions = 1,
            verticalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = 360.dp,
            excludedBounds = emptyList(),
        )

    @Test
    fun `a window decides by its room`() {
        assertEquals(false, detailBesideList(spatial = false, directive(1)))
        assertEquals(true, detailBesideList(spatial = false, directive(2)))
    }

    @Test
    fun `Full Space always has room, whatever the hidden window says`() {
        assertEquals(true, detailBesideList(spatial = true, directive(1)))
    }
}
