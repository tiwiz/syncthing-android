package com.nutomic.syncthingandroid.util

import android.app.Dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The parts of [Util] that run shell commands: processes, scripts, sync conflicts and the TCP port
 * check. [ShadowUtilShellRecorder] answers the commands instead of the host's shell, and records
 * them; nothing is ever killed.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    shadows = [UtilShellTest.ShadowUtilShellRecorder::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class UtilShellTest {

    /** Replaces the two shell runners of Util; tests script the answers. */
    // Robolectric instantiates shadow classes, so this cannot be an object.
    @Suppress("UtilityClassWithPublicConstructor")
    @Implements(className = "com.nutomic.syncthingandroid.util.Util")
    class ShadowUtilShellRecorder {
        companion object {
            /** Answers for `runShellCommandGetOutput`, in order; then "". */
            val outputs = ArrayDeque<String>()
            val outputCommands = mutableListOf<String>()
            val commands = mutableListOf<String>()
            var exitCode = 0

            @JvmStatic
            @Implementation
            fun runShellCommandGetOutput(cmd: String): String {
                outputCommands += cmd
                return outputs.removeFirstOrNull().orEmpty()
            }

            @JvmStatic
            @Implementation
            fun runShellCommand(cmd: String): Int {
                commands += cmd
                return exitCode
            }
        }
    }

    private val outputs = ShadowUtilShellRecorder.outputs
    private val tempDir: File = Files.createTempDirectory("scripts").toFile()

    @Before
    fun setUp() {
        outputs.clear()
        ShadowUtilShellRecorder.outputCommands.clear()
        ShadowUtilShellRecorder.commands.clear()
        ShadowUtilShellRecorder.exitCode = 0
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `process ids are the second column of the matching ps rows`() {
        outputs += PS

        assertEquals(listOf("4711", "4712"), getProcessPIDs("libsyncthingnative.so"))
        assertEquals(listOf("ps\n"), ShadowUtilShellRecorder.outputCommands)
    }

    @Test
    fun `no ps output means no processes`() {
        assertEquals(emptyList<String>(), getProcessPIDs("libsyncthingnative.so"))
    }

    @Test
    fun `killing a process interrupts every instance and waits until they are gone`() {
        outputs += PS
        outputs += PS
        outputs += ""

        killProcess("libsyncthingnative.so")

        assertEquals(
            listOf("kill -SIGINT 4711\n", "kill -SIGINT 4712\n"),
            ShadowUtilShellRecorder.commands,
        )
        assertEquals(3, ShadowUtilShellRecorder.outputCommands.size)
    }

    @Test
    fun `killing a process that does not run does nothing`() {
        outputs += PS

        killProcess("find")

        assertEquals(emptyList<String>(), ShadowUtilShellRecorder.commands)
    }

    @Test
    fun `a failed kill is only logged`() {
        ShadowUtilShellRecorder.exitCode = 1
        outputs += PS
        outputs += ""

        killProcess("libsyncthingnative.so")

        assertEquals(2, ShadowUtilShellRecorder.commands.size)
    }

    @Test
    fun `the port check finds listening tcp and tcp6 sockets only`() {
        val netstat =
            """
            Proto Recv-Q Send-Q Local Address Foreign Address State
            tcp 0 0 127.0.0.1:8384 0.0.0.0:* LISTEN
            tcp6 0 0 :::22000 :::* LISTEN
            tcp 0 0 127.0.0.1:9000 127.0.0.1:5555 ESTABLISHED
            udp 0 0 0.0.0.0:21027 0.0.0.0:* LISTEN
            """
                .trimIndent()
        repeat(5) { outputs += netstat }

        assertTrue(isTcpPortListening(8384))
        assertTrue(isTcpPortListening(22000))
        assertFalse(isTcpPortListening(9000))
        assertFalse(isTcpPortListening(21027))
        assertFalse(isTcpPortListening(84))
        assertFalse(isTcpPortListening(8384))
    }

    @Test
    fun `sync conflicts are the lines find prints`() {
        outputs +=
            "a.sync-conflict-20250514-140912-XF3FVSV\nsub/b.sync-conflict-20250514-140912-XF3FVSV\n"

        val conflicts = getSyncConflictFiles("/storage/emulated/0/DCIM")

        assertArrayEquals(
            arrayOf(
                "a.sync-conflict-20250514-140912-XF3FVSV",
                "sub/b.sync-conflict-20250514-140912-XF3FVSV",
            ),
            conflicts,
        )
        val digit = "[0-9]"
        val alnum = "[a-zA-Z0-9]"
        assertEquals(
            "cd \"/storage/emulated/0/DCIM/\";find -type f -name \"*\\.sync-conflict-" +
                digit.repeat(8) +
                "-" +
                digit.repeat(6) +
                "-" +
                alnum.repeat(7) +
                "*\"" +
                " -not -path \"\\.\\/\\.stversions\\/*\" -print | sed \"s~\\\\.\\/~~\"",
            ShadowUtilShellRecorder.outputCommands.single(),
        )
        assertArrayEquals(arrayOf<String>(), getSyncConflictFiles("/x"))
    }

    @Test
    fun `scripts in a folder run with the folder's parent as working directory`() {
        File(tempDir, "done.sh").writeText("echo done")
        File(tempDir, "readme.txt").writeText("not a script")

        runScriptSet(tempDir.path, arrayOf("Camera", "say \"hi\""))

        assertEquals(
            listOf(
                "cd \"${tempDir.path}/..\";sh \"${File(tempDir, "done.sh").absolutePath}\"" +
                    " \"Camera\" \"say \\\"hi\\\"\""
            ),
            ShadowUtilShellRecorder.outputCommands,
        )
    }

    @Test
    fun `no scripts or no folder means nothing runs`() {
        runScriptSet(tempDir.path, null)
        runScriptSet(File(tempDir, "missing").path, null)

        assertEquals(emptyList<String>(), ShadowUtilShellRecorder.outputCommands)
    }

    @Test
    fun `a write test that fails reports a read-only folder`() {
        ShadowUtilShellRecorder.exitCode = 1

        assertFalse(nativeBinaryCanWriteToPath(null, "/sdcard/x"))
        assertEquals(
            listOf("echo \"\" > \"/sdcard/x/.stwritetest\"\n"),
            ShadowUtilShellRecorder.commands,
        )
    }

    @Test
    fun `a write test that works removes its file`() {
        assertTrue(nativeBinaryCanWriteToPath(null, "/sdcard/x"))
        assertEquals(
            listOf("echo \"\" > \"/sdcard/x/.stwritetest\"\n", "rm \"/sdcard/x/.stwritetest\"\n"),
            ShadowUtilShellRecorder.commands,
        )
    }

    @Test
    fun `a dialog is dismissed only while showing and the screen lives`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val activity = controller.get()
        val dialog = Dialog(activity).apply { show() }

        dismissDialogSafe(null, activity)
        dismissDialogSafe(dialog, activity)
        assertFalse(dialog.isShowing)

        val second = Dialog(activity).apply { show() }
        activity.finish()
        dismissDialogSafe(second, activity)
        assertTrue(second.isShowing)
    }

    @Test
    fun `without the OS CA store there is no OS trust manager, and it is not retried`() {
        assertNull(getOsTrustManager())
        assertSame(getOsTrustManager(), getOsTrustManager())
    }

    private companion object {
        val PS =
            """
            USER      PID   PPID  VSZ    RSS   WCHAN  ADDR S NAME
            u0_a123   4711  1     12345  6789  0      0    S libsyncthingnative.so
            u0_a123   4712  1     12345  6789  0      0    S libsyncthingnative.so
            u0_a123   4800  1     1234   678   0      0    S sh
            """
                .trimIndent()
    }
}
