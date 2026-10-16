package com.nutomic.syncthingandroid.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.views.StateTone
import org.junit.Assert.assertEquals
import org.junit.Test

class StateLookTest {

    @Test
    fun `each tone has its shape and container colors`() {
        val expected =
            mapOf(
                StateTone.OK to (StateShape.FLOWER to StateColors.TERTIARY),
                StateTone.BUSY to (StateShape.COOKIE to StateColors.PRIMARY),
                StateTone.ATTENTION to (StateShape.SUNNY to StateColors.SECONDARY),
                StateTone.PAUSED to (StateShape.SUNNY to StateColors.SECONDARY),
                StateTone.ERROR to (StateShape.COOKIE to StateColors.ERROR),
                StateTone.OFFLINE to (StateShape.CIRCLE to StateColors.NEUTRAL),
            )
        for ((tone, shapeAndColors) in expected) {
            assertEquals(tone.name, shapeAndColors.first, tone.look().shape)
            assertEquals(tone.name, shapeAndColors.second, tone.look().colors)
        }
    }

    @Test
    fun `tones can be told apart without color`() {
        val looks = StateTone.entries.map { it.look().shape to it.look().icon }

        assertEquals(looks.size, looks.toSet().size)
    }

    @Test
    fun `a single grouped item is fully rounded`() {
        assertEquals(RoundedCornerShape(24.dp), groupedItemShape(index = 0, count = 1))
    }

    @Test
    fun `grouped items are rounded outside the group and tight inside it`() {
        assertEquals(
            RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp),
            groupedItemShape(index = 0, count = 3),
        )
        assertEquals(RoundedCornerShape(6.dp), groupedItemShape(index = 1, count = 3))
        assertEquals(
            RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp),
            groupedItemShape(index = 2, count = 3),
        )
    }
}
