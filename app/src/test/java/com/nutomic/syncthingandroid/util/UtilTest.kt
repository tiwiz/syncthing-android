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
            sizes.forEach { appendLine("size $it -> ${readableFileSize(context, it)}") }
            rates.forEach { appendLine("rate $it bits -> ${readableTransferRate(context, it)}") }
        }
        Golden.assertMatches("util/readable-sizes.txt", table)
    }

    @Test
    fun `sizes of one PiB and more stay in the largest unit`() {
        val pib = 1024.0 * 1024 * 1024 * 1024 * 1024
        assertEquals("1,024 TB", readableFileSize(context, pib))
        assertEquals("2,048 TB", readableFileSize(context, 2 * pib))
        assertEquals(
            "1,024 TB/s",
            readableTransferRate(context, 8L * 1024 * 1024 * 1024 * 1024 * 1024),
        )
    }

    @Test
    fun `path ellipsis`() {
        // Same inputs as the app's own testPathEllipsis().
        val inputs =
            listOf(
                "",
                "/",
                "//",
                "go2sync.dll",
                "MY-LINK-/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll",
                "MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll",
                "MY-LINK-Iam-bin-sum-another-and-make-long-textfiles-are-cool.txt",
                "MY-LINK-/Cool 12345 Configuration Utility/sdk/" +
                    "bin-Iam-bin-sum-another-and-make-long/go2sync-long-textfiles-are-cool.dll",
                "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//" +
                    "go2sync-long-textfiles-are-cool",
                "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//" +
                    "go2sync-long-textfiles-are-cool.correctlongeextensionswassolldas-denn-bitte",
                "MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/" +
                    "go2sync.dllcorrectlongeextensionswassolldas-denn-bitte",
            )
        Golden.assertMatches(
            "util/path-ellipsis.txt",
            inputs.joinToString("\n") { "[$it] -> [${getPathEllipsis(it)}]" },
        )
    }

    @Test
    fun `date and time formatting converts to the local zone`() {
        val table =
            listOf("2026-10-12T21:37:46.123456789Z", "2026-01-01T00:00:00+02:00").joinToString(
                "\n"
            ) {
                "$it -> ${formatDateTime(it)} | ${formatTime(it)}"
            }
        Golden.assertMatches("util/date-time.txt", table)
    }

    @Test
    fun `format path normalizes dot segments`() {
        assertEquals(
            "/storage/emulated/0/DCIM/",
            formatPath("/storage/emulated/0/Music/../DCIM/./"),
        )
        assertEquals("/a/b", formatPath("/a//b"))
    }

    @Test
    fun `contains ignore case`() {
        assertTrue(containsIgnoreCase("Syncthing", "THING"))
        assertTrue(containsIgnoreCase("Syncthing", ""))
        assertFalse(containsIgnoreCase("Sync", "Syncthing"))
        assertFalse(containsIgnoreCase("Syncthing", "x"))
    }

    @Test
    fun `local zoned date time is an ISO offset timestamp in UTC`() {
        assertTrue(
            getLocalZonedDateTime().matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z"""))
        )
    }

    @Test
    fun `not running on a TV by default`() {
        assertFalse(isRunningOnTV(context))
    }

    // The real shell, on the host: these commands behave the same in sh on Linux and macOS.

    @Test
    fun `a shell command reports its exit code`() {
        assertEquals(0, runShellCommand("true\n"))
        assertEquals(3, runShellCommand("exit 3\n"))
    }

    @Test
    fun `a shell command's output is captured line by line`() {
        assertEquals("a\nb\n", runShellCommandGetOutput("echo a; echo b\n"))
        assertEquals("", runShellCommandGetOutput("exit 2\n"))
    }
}
