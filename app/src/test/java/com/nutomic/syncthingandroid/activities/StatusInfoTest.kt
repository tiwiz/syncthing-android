package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingService.State
import com.nutomic.syncthingandroid.views.StateTone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the status tab shows, as blocks: headline, tone, reasons and figures. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class StatusInfoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val figures = SystemFigures("5m", "1 MB", "0 B/s (0 B)", "0 B/s (0 B)", "")

    @Test
    fun `each state has its tone`() {
        val tones =
            mapOf(
                State.INIT to StateTone.BUSY,
                State.STARTING to StateTone.BUSY,
                State.ACTIVE to StateTone.OK,
                State.DISABLED to StateTone.OFFLINE,
                State.ERROR to StateTone.ERROR,
            )
        for ((state, tone) in tones) {
            assertEquals(state.name, tone, statusInfo(context, state, "x", null).tone)
        }
    }

    @Test
    fun `only a starting Syncthing shows a loading indicator`() {
        for (state in State.entries) {
            assertEquals(
                state.name,
                state == State.STARTING,
                statusInfo(context, state, "x", null).starting,
            )
        }
    }

    @Test
    fun `the reasons are one per line of the explanation`() {
        val info = statusInfo(context, State.DISABLED, " On battery\nNo Wi-Fi\n", figures)

        assertEquals("Syncthing is not running.", info.headline)
        assertEquals(listOf("On battery", "No Wi-Fi"), info.reasons)
        assertEquals(emptyList<Pair<String, String>>(), info.figures)
    }

    @Test
    fun `while running the figures are labelled, empty ones left out`() {
        val info = statusInfo(context, State.ACTIVE, "Forced", figures)

        assertEquals(
            listOf(
                "Uptime" to "5m",
                "RAM Usage" to "1 MB",
                "Download" to "0 B/s (0 B)",
                "Upload" to "0 B/s (0 B)",
            ),
            info.figures,
        )
    }

    @Test
    fun `starting or crashed there are no reasons`() {
        assertEquals(emptyList<String>(), statusInfo(context, State.STARTING, "x", null).reasons)
        assertEquals(emptyList<String>(), statusInfo(context, State.ERROR, "x", null).reasons)
    }
}
