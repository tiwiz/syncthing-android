package com.nutomic.syncthingandroid.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** The grouped item shape's morph and the reduced motion check. */
@RunWith(AndroidJUnit4::class)
class GroupedTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun show(content: @Composable () -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
            .setContent(content = content)
    }

    @Test
    fun `an emphasized item morphs to fully rounded and back`() {
        var emphasized by mutableStateOf(false)
        var shape: Shape? = null
        show { ApplicationTheme { shape = morphingItemShape(index = 1, count = 3, emphasized) } }
        assertEquals(RoundedCornerShape(6.dp), shape)

        compose.mainClock.autoAdvance = false
        emphasized = true
        Snapshot.sendApplyNotifications()
        // The first frame that moves shows a shape between the two: a morph, not a jump.
        var frames = 0
        while (shape == RoundedCornerShape(6.dp) && frames++ < MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
        }
        assertNotEquals(RoundedCornerShape(6.dp), shape)
        assertNotEquals(RoundedCornerShape(24.dp), shape)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(RoundedCornerShape(24.dp), shape)

        emphasized = false
        compose.waitForIdle()
        assertEquals(RoundedCornerShape(6.dp), shape)
    }

    @Test
    fun `animations are off when the system animator scale is zero`() {
        val resolver =
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var enabled: Boolean? = null
        show { enabled = rememberAnimationsEnabled() }

        assertFalse(checkNotNull(enabled))
    }

    @Test
    fun `animations are on by default`() {
        var enabled: Boolean? = null
        show { enabled = rememberAnimationsEnabled() }

        assertTrue(checkNotNull(enabled))
    }

    private companion object {
        /** More frames than the morph needs to start. */
        const val MAX_FRAMES = 10
    }
}
