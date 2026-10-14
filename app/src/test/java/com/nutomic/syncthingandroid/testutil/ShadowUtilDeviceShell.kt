package com.nutomic.syncthingandroid.testutil

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * `Util.runShellCommandGetOutput` runs the host's `sh` in tests, so its answers (e.g. `ulimit -n`)
 * differ between machines. This shadow answers like a typical Android device instead. It needs
 * `@Config(instrumentedPackages = ["com.nutomic.syncthingandroid.util"])` to apply to app code.
 */
// Robolectric instantiates shadow classes, so this cannot be an object.
@Suppress("UtilityClassWithPublicConstructor")
@Implements(className = "com.nutomic.syncthingandroid.util.Util")
class ShadowUtilDeviceShell {
    companion object {
        val FAKE_LOGCAT =
            """
            10-13 10:00:00.000 I/SyncthingService(1234): onServiceStateChange: from DISABLED to STARTING
            10-13 10:00:01.000 I/SyncthingService(1234): Web GUI will be available at https://127.0.0.1:8384
            10-13 10:00:02.000 I/SyncthingService(1234): onServiceStateChange: from STARTING to ACTIVE
            """
                .trimIndent() + "\n"

        /** What logcat prints (the log screen). Tests reset it. */
        @Volatile var logcatOutput: String = FAKE_LOGCAT

        /** What `netstat -t -l -n` prints (`Util.isTcpPortListening`). Tests reset it. */
        @Volatile var netstatOutput: String = ""

        /** What the sync-conflict `find` in `Util.getSyncConflictFiles` prints. Tests reset it. */
        @Volatile var syncConflictFindOutput: String = ""

        @JvmStatic
        @Implementation
        fun runShellCommandGetOutput(cmd: String): String =
            when {
                cmd.contains("find -type f -name") && cmd.contains(".sync-conflict-") ->
                    syncConflictFindOutput
                cmd.startsWith("/system/bin/logcat ") -> logcatOutput
                cmd.startsWith("netstat ") -> netstatOutput
                cmd.endsWith("ulimit -n") -> "32768\n"
                cmd.startsWith("/system/bin/du -sh ") ->
                    "12M\t${cmd.removePrefix("/system/bin/du -sh ")}\n"
                else -> ""
            }
    }
}
