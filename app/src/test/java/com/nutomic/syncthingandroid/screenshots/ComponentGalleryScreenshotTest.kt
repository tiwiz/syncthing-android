package com.nutomic.syncthingandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.ui.SectionHeader
import com.nutomic.syncthingandroid.ui.StateBadge
import com.nutomic.syncthingandroid.ui.SyncProgress
import com.nutomic.syncthingandroid.ui.groupedItemShape
import com.nutomic.syncthingandroid.views.StateTone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared expressive components (`ui/`): state badges, a grouped list, wavy progress. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ComponentGalleryScreenshotTest(private val dark: Boolean) {

    @get:Rule val stillAnimations = StillAnimationsRule()

    @Test
    fun gallery() {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ApplicationTheme { Surface { Gallery() } } }
        settle()
        activity.window.decorView.captureRoboImage(screenshotPath("components", "gallery", dark))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}

@Composable
private fun Gallery() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SectionHeader("States")
        for (tone in StateTone.entries) {
            val index = tone.ordinal
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(bottom = 3.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            groupedItemShape(index, StateTone.entries.size),
                        )
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StateBadge(tone)
                Text(tone.name, style = MaterialTheme.typography.titleMedium)
            }
        }
        SectionHeader("Progress")
        SyncProgress(progress = 0.42f)
    }
}
