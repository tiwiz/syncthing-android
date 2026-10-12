package com.nutomic.syncthingandroid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.Golden
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class UtilTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before
    fun pinLocaleAndZone() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
    }

    @After
    fun restoreLocaleAndZone() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun `readable file sizes and transfer rates`() {
        val sizes = listOf(-1.0, 0.0, 1.0, 1023.0, 1024.0, 1536.0, 1048576.0, 5.5e9, 3.2e12, 1.1e15)
        val rates = listOf(-8L, 0L, 7L, 8L, 8_192L, 1_000_000L, 800_000_000L, 80_000_000_000L)
        val table = buildString {
            sizes.forEach { appendLine("size $it -> ${Util.readableFileSize(context, it)}") }
            rates.forEach { appendLine("rate $it bits -> ${Util.readableTransferRate(context, it)}") }
        }
        Golden.assertMatches("util/readable-sizes.txt", table)
    }

    @Test
    fun `sizes of one PiB and more crash`() {
        // CHARACTERIZATION: the unit arrays stop at TiB, so 1024^5 bytes and above index past the end.
        assertThrows(ArrayIndexOutOfBoundsException::class.java) {
            Util.readableFileSize(context, 1024.0 * 1024 * 1024 * 1024 * 1024)
        }
        assertThrows(ArrayIndexOutOfBoundsException::class.java) {
            Util.readableTransferRate(context, 8L * 1024 * 1024 * 1024 * 1024 * 1024)
        }
    }

    @Test
    fun `path ellipsis`() {
        // Same inputs as the app's own Util.testPathEllipsis().
        val inputs =
            listOf(
                "",
                "/",
                "//",
                "go2sync.dll",
                "MY-LINK-/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll",
                "MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll",
                "MY-LINK-Iam-bin-sum-another-and-make-long-textfiles-are-cool.txt",
                "MY-LINK-/Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long/go2sync-long-textfiles-are-cool.dll",
                "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//go2sync-long-textfiles-are-cool",
                "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//go2sync-long-textfiles-are-cool.correctlongeextensionswassolldas-denn-bitte",
                "MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/go2sync.dllcorrectlongeextensionswassolldas-denn-bitte",
            )
        Golden.assertMatches(
            "util/path-ellipsis.txt",
            inputs.joinToString("\n") { "[$it] -> [${Util.getPathEllipsis(it)}]" },
        )
    }

    @Test
    fun `date and time formatting converts to the local zone`() {
        val table =
            listOf("2026-10-12T21:37:46.123456789Z", "2026-01-01T00:00:00+02:00").joinToString("\n") {
                "$it -> ${Util.formatDateTime(it)} | ${Util.formatTime(it)}"
            }
        Golden.assertMatches("util/date-time.txt", table)
    }

    @Test
    fun `format path normalizes dot segments`() {
        assertEquals("/storage/emulated/0/DCIM/", Util.formatPath("/storage/emulated/0/Music/../DCIM/./"))
        assertEquals("/a/b", Util.formatPath("/a//b"))
    }

    @Test
    fun `contains ignore case`() {
        assertTrue(Util.containsIgnoreCase("Syncthing", "THING"))
        assertTrue(Util.containsIgnoreCase("Syncthing", ""))
        assertFalse(Util.containsIgnoreCase("Sync", "Syncthing"))
        assertFalse(Util.containsIgnoreCase("Syncthing", "x"))
    }

    @Test
    fun `local zoned date time is an ISO offset timestamp in UTC`() {
        assertTrue(Util.getLocalZonedDateTime().matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z""")))
    }

    @Test
    fun `not running on a TV by default`() {
        assertFalse(Util.isRunningOnTV(context))
    }
}
