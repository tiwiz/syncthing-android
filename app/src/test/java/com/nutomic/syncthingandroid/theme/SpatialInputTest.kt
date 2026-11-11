package com.nutomic.syncthingandroid.theme

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode

/** What Full Space changes for a hand ray: larger targets and text, a visible focus. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpatialInputTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun show(content: @Composable () -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            content()
        }
        compose.waitForIdle()
    }

    private fun capture(spatial: Boolean): Pair<Dp, Typography> {
        var captured: Pair<Dp, Typography>? = null
        show {
            ApplicationTheme(spatial = spatial) {
                captured = LocalMinimumInteractiveComponentSize.current to MaterialTheme.typography
            }
        }
        return checkNotNull(captured)
    }

    @Test
    fun `in Full Space targets are 56 dp and no text is under 14 sp`() {
        val (target, type) = capture(spatial = true)

        assertEquals(56.dp, target)
        listOf(
                type.bodySmall,
                type.labelMedium,
                type.labelSmall,
                type.bodySmallEmphasized,
                type.labelMediumEmphasized,
                type.labelSmallEmphasized,
            )
            .forEach { assertTrue("${it.fontSize}", it.fontSize.value >= 14f) }
    }

    @Test
    fun `larger text keeps a line height that fits it`() {
        val style = AppTypography.forSpatial().labelSmall
        assertEquals(14.sp, style.fontSize)
        assertTrue("${style.lineHeight}", style.lineHeight.value >= 20f)
    }

    @Test
    fun `text already large enough is left alone`() {
        assertEquals(AppTypography.bodyMedium, AppTypography.forSpatial().bodyMedium)
    }

    @Test
    fun `elsewhere the sizes are the theme's own`() {
        val (target, type) = capture(spatial = false)

        assertEquals(48.dp, target)
        assertEquals(AppTypography, type)
    }

    private fun showFocused(spatial: Boolean) {
        val focus = FocusRequester()
        show {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.size(100.dp)
                    .testTag("box")
                    .then(focusRing(source, RectangleShape, Color.Red, spatial))
                    .focusRequester(focus)
                    .focusable(interactionSource = source)
            )
        }
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
    }

    @Test
    fun `in Full Space a focused row has a ring`() {
        showFocused(spatial = true)

        val image = compose.onNodeWithTag("box").captureToImage().toPixelMap()
        assertEquals(Color.Red, image[1, image.height / 2])
    }

    @Test
    fun `elsewhere a focused row has no ring`() {
        showFocused(spatial = false)

        val image = compose.onNodeWithTag("box").captureToImage().toPixelMap()
        assertNotEquals(Color.Red, image[1, image.height / 2])
    }
}
