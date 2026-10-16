package com.nutomic.syncthingandroid.theme

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ApplicationThemeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Captured(
        val colors: ColorScheme,
        val typography: Typography,
        val motion: MotionScheme,
    )

    private fun capture(): Captured {
        var captured: Captured? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ApplicationTheme {
                captured =
                    Captured(
                        MaterialTheme.colorScheme,
                        MaterialTheme.typography,
                        MaterialTheme.motionScheme,
                    )
            }
        }
        return checkNotNull(captured)
    }

    /** ColorScheme has no value equality; its string lists every role. */
    private fun assertSameColors(expected: ColorScheme, actual: ColorScheme) =
        assertEquals(expected.toString(), actual.toString())

    @Test
    @Config(sdk = [30])
    fun `without dynamic color the light scheme comes from the Syncthing seed`() {
        assertSameColors(SyncthingLightColors, capture().colors)
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun `without dynamic color the dark scheme comes from the Syncthing seed`() {
        assertSameColors(SyncthingDarkColors, capture().colors)
    }

    @Test
    fun `dynamic color is used when the system has it`() {
        assertSameColors(dynamicLightColorScheme(context), capture().colors)
    }

    @Test
    @Config(qualifiers = "night")
    fun `dynamic dark color is used in night mode`() {
        assertSameColors(dynamicDarkColorScheme(context), capture().colors)
    }

    @Test
    fun `text uses Roboto Flex, display and headline text its wide cut`() {
        val typography = capture().typography
        assertSame(AppFonts.text, typography.bodyLarge.fontFamily)
        assertSame(AppFonts.text, typography.labelLarge.fontFamily)
        assertSame(AppFonts.text, typography.titleMedium.fontFamily)
        assertSame(AppFonts.wide, typography.displaySmall.fontFamily)
        assertSame(AppFonts.wide, typography.headlineMedium.fontFamily)
    }

    @Test
    fun `motion is expressive`() {
        // Each call makes a new instance: compare the kind.
        assertEquals(MotionScheme.expressive().javaClass, capture().motion.javaClass)
    }
}
