package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.service.SyncthingService.State
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The lines and figures of the main screen's status tab. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class StatusLinesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val figures = SystemFigures("5m", "1 MB", "0 B/s (0 B)", "0 B/s (0 B)", "")

    @Test
    fun `each state says what Syncthing does, and running or stopped why`() {
        assertEquals(listOf("Syncthing is starting."), statusLines(context, State.INIT, "x", null))
        assertEquals(
            listOf("Syncthing is starting."),
            statusLines(context, State.STARTING, "x", null),
        )
        assertEquals(listOf("Syncthing has crashed."), statusLines(context, State.ERROR, "x", null))
        assertEquals(
            listOf("Syncthing is not running.", "Reason:\n- On battery\n- No Wi-Fi"),
            statusLines(context, State.DISABLED, " On battery\nNo Wi-Fi\n", figures),
        )
    }

    @Test
    fun `while running the figures follow, empty ones left out`() {
        assertEquals(
            listOf(
                "Syncthing is running.",
                "Reason:\n- Forced",
                "Uptime: 5m",
                "RAM Usage: 1 MB",
                "Download: 0 B/s (0 B)",
                "Upload: 0 B/s (0 B)",
            ),
            statusLines(context, State.ACTIVE, "Forced", figures),
        )
        assertEquals(2, statusLines(context, State.ACTIVE, "Forced", null).size)
    }

    @Test
    fun `uptime in minutes, hours or days`() {
        assertEquals("0m", readableUptime(59))
        assertEquals("3m", readableUptime(3 * 60))
        assertEquals("2h 03m", readableUptime(2 * 3600 + 3 * 60))
        assertEquals("1d 02h 03m", readableUptime(86_400 + 2 * 3600 + 3 * 60))
    }

    @Test
    fun `figures hide slow rates and count reachable announce servers`() {
        val status =
            SystemStatus().apply {
                uptime = 60
                sys = 2048
                discoveryMethods = 4
                discoveryErrors = mapOf("global@a" to "timeout")
            }
        val total =
            Connection().apply {
                inBits = 1023L * 8
                outBits = 4096L * 8
                inBytesTotal = 0
                outBytesTotal = 2048
            }

        assertEquals(
            SystemFigures("1m", "2 KB", "0 B/s (0 B)", "4 KB/s (2 KB)", "3/4"),
            systemFigures(context, status, total),
        )
        assertEquals("", systemFigures(context, SystemStatus(), Connection()).announceServer)
    }
}
