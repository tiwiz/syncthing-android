package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.text.TextUtils
import android.util.Log
import com.google.common.io.Files
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/** Runs the Syncthing binary with one of its [Command]s. */
class SyncthingRunnable(
    private val context: Context,
    command: Command,
    private val preferences: SharedPreferences,
    private val notificationHandler: NotificationHandler,
) : Runnable {

    private val enableVerboseLog: Boolean
    // e.g. "/data/app/${applicationId}-8HsN-IsVtZXc8GrE5-Hepw==/lib/x86/libsyncthingnative.so"
    private val syncthingBinary: File = Constants.getSyncthingBinary(context)
    private val syncthingLogFile: File = Constants.getSyncthingLogFile(context)
    private val commandLine: Array<String>

    enum class Command {
        /** Output the device ID to the command line. */
        DEVICE_ID,
        /** Generate keys, a config file and immediately exit. */
        GENERATE,
        /** Run the main Syncthing application. */
        MAIN,
        /** Reset Syncthing's database */
        RESET_DATABASE,
        /** Reset Syncthing's delta indexes */
        RESET_DELTAS,
    }

    class ExecutableNotFoundException(message: String) : Exception(message)

    init {
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        val binary = syncthingBinary.path
        commandLine =
            when (command) {
                Command.DEVICE_ID -> arrayOf(binary, "device-id")
                Command.GENERATE -> arrayOf(binary, "generate")
                Command.MAIN -> arrayOf(binary, "serve", "--no-browser")
                Command.RESET_DATABASE -> arrayOf(binary, "debug", "reset-database")
                Command.RESET_DELTAS -> arrayOf(binary, "serve", "--debug-reset-delta-idxs")
            }
    }

    override fun run() {
        try {
            run(false)
        } catch (e: ExecutableNotFoundException) {
            // The service's thread handler shows the crash notification for it.
            throw IllegalStateException(e.message, e)
        }
    }

    /**
     * Runs the command and waits for it. With [returnStdOut] the output is returned; otherwise it
     * goes to the Syncthing log, and an exit that needs it restarts or stops the service.
     */
    @Throws(ExecutableNotFoundException::class)
    fun run(returnStdOut: Boolean): String {
        var exit = Exit.NONE
        val capturedStdOut = StringBuilder()
        // Trim Syncthing log.
        trimSyncthingLogFile()
        var multicastLock: WifiManager.MulticastLock? = null
        var process: Process? = null
        try {
            // Android 11 blocks local discovery if we did not acquire MulticastLock.
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            multicastLock =
                wifi.createMulticastLock("multicastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            // Set up and run a new syncthing instance.
            process = setupAndLaunch(buildEnvironment())
            syncthing.set(process)
            var logThreads = emptyList<Thread>()
            if (returnStdOut) {
                readStdOut(process, capturedStdOut)
            } else {
                logThreads = listOf(log(process.inputStream), log(process.errorStream))
            }
            val exitCode = process.waitFor()
            logV("Syncthing exited with code $exitCode")
            syncthing.set(null)
            logThreads.forEach(Thread::join)
            exit = handleExitCode(exitCode)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } finally {
            multicastLock?.release()
            process?.destroy()
        }
        if (!returnStdOut) {
            when (exit) {
                // Restart syncthing if it exited unexpectedly while running on a separate thread.
                Exit.RESTART ->
                    context.startService(
                        Intent(context, SyncthingService::class.java)
                            .setAction(SyncthingService.ACTION_RESTART)
                    )
                // Notify SyncthingService that service state State.ACTIVE is no longer valid.
                Exit.STOP ->
                    context.startService(
                        Intent(context, SyncthingService::class.java)
                            .setAction(SyncthingService.ACTION_STOP)
                            .putExtra(SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE, true)
                    )
                Exit.NONE -> Unit
            }
        }
        return capturedStdOut.toString()
    }

    private enum class Exit {
        NONE,
        RESTART,
        STOP,
    }

    private fun readStdOut(process: Process, capturedStdOut: StringBuilder) {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG_NATIVE, line)
                    capturedStdOut.append(line).append('\n')
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Syncthing's command line output", e)
        }
    }

    /**
     * What to do after the process exited with [exitCode]; shows a crash notification if needed.
     */
    private fun handleExitCode(exitCode: Int): Exit {
        val crash: String
        when (exitCode) {
            0,
            EXIT_SIGKILL -> {
                Log.i(
                    TAG,
                    "Syncthing was shut down normally via API or SIGKILL. Exit code = $exitCode",
                )
                return Exit.NONE
            }
            // Restart was requested via Rest API call.
            EXIT_RESTARTING -> {
                Log.i(TAG, "exit reason = exitRestarting. Restarting syncthing.")
                return Exit.RESTART
            }
            EXIT_ERROR ->
                crash =
                    "exit reason = exitError. Another Syncthing instance may be already running."
            // This should not happen as STNOUPGRADE is set.
            EXIT_NO_UPGRADE ->
                crash =
                    "exit reason = exitNoUpgradeAvailable. Another Syncthing instance may be already running."
            // Native was force killed.
            EXIT_FORCE_KILL -> crash = "exit reason = exitForceKill."
            EXIT_INVALID_COMMAND_LINE -> crash = "exit reason = exitInvalidCommandLine."
            else -> crash = "Syncthing exited unexpectedly. Exit code = $exitCode"
        }
        Log.w(TAG, crash)
        notificationHandler.showCrashedNotification(
            R.string.notification_crash_title,
            exitCode.toString(),
        )
        return Exit.STOP
    }

    private fun putCustomEnvironmentVariables(environment: MutableMap<String, String>) {
        val customEnvironment = preferences.getString(Constants.PREF_ENVIRONMENT_VARIABLES, null)
        if (TextUtils.isEmpty(customEnvironment)) return
        for (e in checkNotNull(customEnvironment).splitLikeJavaSpaces()) {
            val e2 = e.split("=", limit = 2)
            logV("Setting env var: [${e2[0]}]=[${e2[1]}]")
            environment[e2[0]] = e2[1]
        }
    }

    /** Appends the lines of [stream] to the Syncthing log, on a new thread. */
    private fun log(stream: InputStream): Thread = Thread {
        try {
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    // Always output SyncthingNative's output to "syncthing.log".
                    @Suppress("DEPRECATION") // Guava's append; kept from the Java original.
                    Files.append(line + "\n", syncthingLogFile, Charsets.UTF_8)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Syncthing's command line output", e)
        }
    }
        .apply { start() }

    /** Only keeps the last [LOG_FILE_MAX_LINES] lines in the log file, to avoid bloat. */
    private fun trimSyncthingLogFile() {
        if (!syncthingLogFile.exists()) {
            return
        }
        try {
            RandomAccessFile(syncthingLogFile, "r").use { input ->
                val buf = ByteArray(LOG_FILE_BUFFER_SIZE)
                val length = input.length()
                val truncationOffset = findTruncationOffset(input, buf, length)
                // The file already contains fewer than maximum lines.
                if (truncationOffset < 0) return
                input.seek(truncationOffset)
                val tempFile = File(context.filesDir.toString(), "syncthing.log.tmp")
                var remain = length - truncationOffset
                FileOutputStream(tempFile).use { output ->
                    while (remain > 0) {
                        val n = minOf(remain, buf.size.toLong()).toInt()
                        input.readFully(buf, 0, n)
                        output.write(buf, 0, n)
                        remain -= n
                    }
                }
                tempFile.renameTo(syncthingLogFile)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to trim log file", e)
        }
    }

    /**
     * The offset of the (n + 1)th newline from the end, with constant memory: the last n lines are
     * everything after it. Reads in block-aligned chunks if [LOG_FILE_BUFFER_SIZE] is a multiple of
     * the filesystem block size. Negative if the file has no more than n lines.
     */
    private fun findTruncationOffset(input: RandomAccessFile, buf: ByteArray, length: Long): Long {
        val chunks = Math.ceilDiv(length, buf.size.toLong())
        var newlinesRemaining = LOG_FILE_MAX_LINES + 1
        for (chunk in chunks - 1 downTo 0) {
            val offset = buf.size * chunk
            input.seek(offset)
            // Last chunk can be smaller than the whole buffer.
            val n = minOf(length - offset, buf.size.toLong()).toInt()
            input.readFully(buf, 0, n)
            val ret = findNthLastNewline(buf, n, newlinesRemaining)
            if (ret >= 0) {
                return offset + ret + 1
            }
            newlinesRemaining = -ret
        }
        return -1
    }

    private fun buildEnvironment(): Map<String, String> {
        val targetEnv = HashMap<String, String>()
        // Set home directory to data folder for web GUI folder picker.
        targetEnv["HOME"] = FileUtils.getSyncthingTildeAbsolutePath()
        // Set config, key and database directory.
        targetEnv["STHOMEDIR"] = context.filesDir.toString()
        targetEnv["STTRACE"] =
            preferences
                .getStringSet(Constants.PREF_DEBUG_FACILITIES_ENABLED, HashSet())
                .orEmpty()
                .joinToString(" ")
        targetEnv["STMONITORED"] = "1"
        targetEnv["STNOUPGRADE"] = "1"
        targetEnv["STVERSIONEXTRA"] = context.getString(R.string.app_name)
        // Database tuning against slowness.
        targetEnv["SQLITE_TMPDIR"] = context.cacheDir.absolutePath
        // Workaround SyncthingNativeCode denied to read gatewayIP by Android 14+ restriction.
        getGatewayIpV4(context)?.let { targetEnv["FALLBACK_NET_GATEWAY_IPV4"] = it }
        putProxyVariables(targetEnv)
        logV("Setting env var: [GOGC]=[$GOGC_DEFAULT]")
        targetEnv["GOGC"] = GOGC_DEFAULT.toString()
        putCustomEnvironmentVariables(targetEnv)
        return targetEnv
    }

    private fun putProxyVariables(targetEnv: MutableMap<String, String>) {
        if (preferences.getBoolean(Constants.PREF_USE_TOR, false)) {
            targetEnv["all_proxy"] = "socks5://localhost:9050"
            targetEnv["ALL_PROXY_NO_FALLBACK"] = "1"
            return
        }
        val socksProxyAddress =
            checkNotNull(preferences.getString(Constants.PREF_SOCKS_PROXY_ADDRESS, ""))
        if (socksProxyAddress != "") {
            targetEnv["all_proxy"] = socksProxyAddress
        }
        val httpProxyAddress =
            checkNotNull(preferences.getString(Constants.PREF_HTTP_PROXY_ADDRESS, ""))
        if (httpProxyAddress != "") {
            targetEnv["http_proxy"] = httpProxyAddress
            targetEnv["https_proxy"] = httpProxyAddress
        }
    }

    @Throws(IOException::class, ExecutableNotFoundException::class)
    private fun setupAndLaunch(env: Map<String, String>): Process {
        // Check if "libsyncthingnative.so" exists.
        if (commandLine.isNotEmpty() && !File(commandLine[0]).exists()) {
            Log.e(
                TAG,
                "CRITICAL - Syncthing core binary is missing in APK package location " +
                    commandLine[0],
            )
            throw ExecutableNotFoundException(commandLine[0])
        }
        return ProcessBuilder(commandLine.toList()).apply { environment().putAll(env) }.start()
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "SyncthingRunnable"
        private const val TAG_NATIVE = "SyncthingNativeCode"
        private const val LOG_FILE_MAX_LINES = 200_000
        private const val LOG_FILE_BUFFER_SIZE = 1024 * 1024
        private const val GOGC_DEFAULT = 100

        // Syncthing's exit codes.
        private const val EXIT_ERROR = 1
        private const val EXIT_NO_UPGRADE = 2
        private const val EXIT_RESTARTING = 3
        private const val EXIT_FORCE_KILL = 9
        private const val EXIT_INVALID_COMMAND_LINE = 64
        private const val EXIT_SIGKILL = 137

        private val syncthing = AtomicReference<Process?>()

        /**
         * If the [nth] last newline is found within [data]'s first [size] bytes, its offset;
         * otherwise the negative of the newlines still to find, for the next chunk.
         */
        private fun findNthLastNewline(data: ByteArray, size: Int, nth: Int): Int {
            require(nth > 0) { "nth must be positive: $nth" }
            var remaining = nth
            for (i in size - 1 downTo 0) {
                if (data[i] == '\n'.code.toByte()) {
                    remaining--
                    if (remaining == 0) {
                        return i
                    }
                }
            }
            return -remaining
        }

        /** Splits on single spaces, like Java's `split(" ")`. */
        private fun String.splitLikeJavaSpaces(): List<String> =
            if (!contains(' ')) listOf(this) else split(' ').dropLastWhile { it.isEmpty() }

        /** The IPv4 gateway of the active network's default route, or null. */
        @JvmStatic
        fun getGatewayIpV4(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = cm.activeNetwork ?: return null
            val props = cm.getLinkProperties(activeNetwork) ?: return null
            return props.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
                ?.hostAddress
        }
    }
}
