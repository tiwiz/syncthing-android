package com.nutomic.syncthingandroid.testutil

import com.nutomic.syncthingandroid.util.Util
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * `Util.runShellCommandGetOutput` runs the host's `sh` in tests, so its answers (e.g. `ulimit -n`)
 * differ between machines. This shadow answers like a typical Android device instead. It needs
 * `@Config(instrumentedPackages = ["com.nutomic.syncthingandroid.util"])` to apply to app code.
 */
// Robolectric instantiates shadow classes, so this cannot be an object.
@Suppress("UtilityClassWithPublicConstructor")
@Implements(Util::class)
class ShadowUtilDeviceShell {
    companion object {
        /** What the sync-conflict `find` in `Util.getSyncConflictFiles` prints. Tests reset it. */
        @Volatile var syncConflictFindOutput: String = ""

        @JvmStatic
        @Implementation
        fun runShellCommandGetOutput(cmd: String): String =
            when {
                cmd.contains("find -type f -name") && cmd.contains(".sync-conflict-") ->
                    syncConflictFindOutput
                cmd.endsWith("ulimit -n") -> "32768\n"
                cmd.startsWith("/system/bin/du -sh ") ->
                    "12M\t${cmd.removePrefix("/system/bin/du -sh ")}\n"
                else -> ""
            }
    }
}
