package com.nutomic.syncthingandroid.testutil

import android.content.Context
import com.nutomic.syncthingandroid.service.Constants
import java.io.File

/**
 * A stand-in for `libsyncthingnative.so`: a `/bin/sh` script in a temporary `nativeLibraryDir` that
 * records its arguments and environment, prints [stdout] and [stderr], and exits with [exitCode].
 * With [serveUntilStopped], `serve` keeps running until [stop], like the real daemon until it is
 * asked to shut down. Needs a POSIX shell on the host, like the CI runners have.
 */
class FakeSyncthingBinary(context: Context) {

    private val dir = File(context.cacheDir, "fake-native").apply { mkdirs() }
    private val argsFile = File(dir, "args")
    private val envFile = File(dir, "env")
    private val stdoutFile = File(dir, "stdout")
    private val stderrFile = File(dir, "stderr")
    private val stopFile = File(dir, "stop")
    private val generatedConfigFile = File(dir, "generated-config.xml")
    private val binary = File(dir, Constants.FILENAME_SYNCTHING_BINARY)

    var exitCode: Int = 0
    var stdout: String = ""
    var stderr: String = ""
    var serveUntilStopped: Boolean = false

    /** If set, `generate` writes it as `$STHOMEDIR/config.xml`, like the real binary does. */
    var generatedConfig: String? = null

    /** True once the binary has been started at least once. */
    val started: Boolean
        get() = argsFile.exists()

    /** The arguments of the last run, without the binary itself. */
    val args: List<String>
        get() = argsFile.readLines()

    /** The environment of the last run. */
    val env: Map<String, String>
        get() =
            envFile
                .readLines()
                .filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }

    init {
        context.applicationInfo.nativeLibraryDir = dir.path
    }

    /** Writes the script; call after setting [exitCode], [stdout] and [stderr]. */
    fun install() {
        stdoutFile.writeText(stdout)
        stderrFile.writeText(stderr)
        argsFile.delete()
        stopFile.delete()
        val generate =
            generatedConfig
                ?.let {
                    generatedConfigFile.writeText(it)
                    "if [ \"${'$'}1\" = generate ]; then cp '${generatedConfigFile.path}' \"${'$'}STHOMEDIR/config.xml\"; fi"
                }
                .orEmpty()
        val wait =
            if (serveUntilStopped) {
                "if [ \"${'$'}1\" = serve ]; then while [ ! -f '${stopFile.path}' ]; do sleep 0.05; done; fi"
            } else {
                ""
            }
        binary.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > '${argsFile.path}'
            env > '${envFile.path}'
            cat '${stdoutFile.path}'
            cat '${stderrFile.path}' >&2
            $generate
            $wait
            exit $exitCode
            """
                .trimIndent() + "\n"
        )
        binary.setExecutable(true)
    }

    /** Lets a [serveUntilStopped] `serve` exit. */
    fun stop() {
        stopFile.createNewFile()
    }

    fun remove() {
        binary.delete()
    }
}
