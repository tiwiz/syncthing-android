package com.nutomic.syncthingandroid.settings

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The custom HTTPS certificate screen's help row, shown when a certificate fails its checks: it
 * opens the setup guide in this app's wiki.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CustomCertificateHelpTest {

    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `help opens the setup guide in this app's wiki`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            HelpRow(shape = RoundedCornerShape(0))
        }

        compose.onNodeWithText("Help").performClick()

        val opened =
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals(
            "https://github.com/tiwiz/syncthing-android/tree/main/wiki/" +
                "tips-and-tricks/Use-a-custom-HTTPS-certificate-for-the-Web-GUI.md",
            opened?.dataString,
        )
    }
}
