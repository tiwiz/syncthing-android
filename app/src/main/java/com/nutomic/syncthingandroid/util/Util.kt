// Shell commands and processes. Top-level functions in a class named Util: Java keeps calling
// Util.x(), and every call, also from Kotlin and between these functions, stays static, which the
// Robolectric shadows of the shell runners rely on (a shadow left out would run, and kill,
// processes on the test host). Formatting is in TextFormat, UI helpers in UiUtil.
@file:JvmName("Util")

package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nutomic.syncthingandroid.service.Constants
import java.io.File
import java.io.IOException
import java.util.Locale

private const val TAG = "Util"
private const val SHELL_FAILURE = 255
private const val KILL_POLL_MILLIS = 50L

// netstat -t -l -n columns: Proto Recv-Q Send-Q Local-Address Foreign-Address State
private const val NETSTAT_LOCAL_ADDRESS = 3
private const val NETSTAT_STATE = 5

private val WHITESPACE = Regex("\\s+")

/**
 * Whether the syncthing binary would be able to write a file into the given folder given the
 * configured access level.
 */
@Suppress("UNUSED_PARAMETER", "UnusedParameter") // Callers pass a context; it is not needed.
fun nativeBinaryCanWriteToPath(context: Context?, absoluteFolderPath: String): Boolean {
    // Write permission test file.
    val touchFile = "$absoluteFolderPath/.stwritetest"
    val exitCode = runShellCommand("echo \"\" > \"$touchFile\"\n")
    if (exitCode != 0) {
        val error = if (exitCode == 1) "Permission denied" else "Shell execution failed"
        Log.i(TAG, "Failed to write test file '$touchFile', $error")
        return false
    }
    // Detected we have write permission.
    Log.i(TAG, "Successfully wrote test file '$touchFile'")
    // Remove test file. Failing is very unlikely to happen, so we have less error handling.
    if (runShellCommand("rm \"$touchFile\"\n") != 0) {
        Log.i(TAG, "Failed to remove test file")
    }
    return true
}

/** The PIDs of the running processes whose `ps` row contains [processName]. */
fun getProcessPIDs(processName: String): List<String> {
    val output = runShellCommandGetOutput("ps\n")
    if (output.isEmpty()) {
        Log.w(TAG, "getProcessPIDs: Failed to list processes. ps command returned empty.")
        return emptyList()
    }
    return output
        .splitLikeJava("\n")
        .filter { it.contains(processName) }
        .map { it.trim().splitLikeJava(WHITESPACE)[1] }
}

/** Looks for running processes and ends them gracefully. */
fun killProcess(processName: String) {
    val processPIDs = getProcessPIDs(processName)
    if (processPIDs.isEmpty()) {
        Log.v(TAG, "killProcess: Found no running instances of [$processName]")
        return
    }
    for (processPID in processPIDs) {
        val exitCode = runShellCommand("kill -SIGINT $processPID\n")
        if (exitCode != 0) {
            Log.w(
                TAG,
                "killProcess: Failed to send kill SIGINT to process [$processPID] exit code $exitCode",
            )
        }
    }
    // Wait for process to end.
    while (getProcessPIDs(processName).isNotEmpty()) {
        SystemClock.sleep(KILL_POLL_MILLIS)
    }
    Log.d(TAG, "killProcess: No more instances of [$processName] running")
}

/** Runs [cmd] in a shell and returns the exit code; 255 if the shell failed. */
fun runShellCommand(cmd: String): Int {
    Log.d(TAG, "runShellCommand: $cmd")
    return runShell(cmd) { line -> Log.v(TAG, "runShellCommand: $line") }
}

/** Runs [cmd] in a shell and returns its standard output, one "\n" after every line. */
fun runShellCommandGetOutput(cmd: String): String {
    Log.d(TAG, "runShellCommandGetOutput: $cmd")
    val output = StringBuilder()
    val exitCode = runShell(cmd) { line -> output.append(line).append('\n') }
    if (exitCode != 0) {
        Log.i(TAG, "runShellCommandGetOutput: Exited with code $exitCode")
    }
    return output.toString()
}

/** Writes [cmd] to `sh`, hands every output line to [onLine], and returns the exit code. */
private fun runShell(cmd: String, onLine: (String) -> Unit): Int {
    var shellProc: Process? = null
    return try {
        val process = Runtime.getRuntime().exec("sh").also { shellProc = it }
        process.outputStream.bufferedWriter().use { it.write(cmd) }
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach(onLine)
            }
        } catch (e: IOException) {
            Log.w(TAG, "runShell: Failed to read output", e)
        }
        process.waitFor()
    } catch (e: IOException) {
        Log.w(TAG, "runShell: Exception", e)
        SHELL_FAILURE
    } catch (e: InterruptedException) {
        Log.w(TAG, "runShell: Exception", e)
        SHELL_FAILURE
    } finally {
        shellProc?.destroy()
    }
}

/** Whether a TCP socket listens on the local device on [port]. */
fun isTcpPortListening(port: Int): Boolean {
    // t: tcp, l: listening, n: numeric
    val output = runShellCommandGetOutput("netstat -t -l -n")
    if (output.isEmpty()) {
        Log.w(TAG, "isTcpPortListening: Failed to run netstat. Returning false.")
        return false
    }
    return output.splitLikeJava("\n").any { line ->
        val words = line.splitLikeJava(WHITESPACE)
        words.size > NETSTAT_STATE &&
            (words[0] == "tcp" || words[0] == "tcp6") &&
            words[NETSTAT_LOCAL_ADDRESS].endsWith(":$port") &&
            words[NETSTAT_STATE].equals("LISTEN", ignoreCase = true)
    }
}

/**
 * Runs every ".sh" file in [absPath] with [scriptArgs], from the folder's parent. Called by RestApi
 * after a folder completed.
 */
fun runScriptSet(absPath: String, scriptArgs: Array<String>?) {
    val scriptFolder = File(absPath)
    if (!scriptFolder.exists() || !scriptFolder.isDirectory) {
        Log.w(TAG, "runScriptSet: Folder does not exist or is not of type folder: $absPath")
        return
    }
    // Find all script files within given folder path.
    val scriptFiles = scriptFolder.listFiles { _, name ->
        name.lowercase(Locale.ROOT).endsWith(".sh")
    }
    if (scriptFiles.isNullOrEmpty()) {
        Log.v(TAG, "runScriptSet: No script files found within folder: $absPath")
        return
    }
    for (scriptFile in scriptFiles) {
        // Build arguments using shell escape.
        val command = buildString {
            append("cd \"").append(absPath).append("/..\";")
            append("sh \"").append(scriptFile.absolutePath).append("\"")
            scriptArgs?.forEach { append(" \"").append(it.replace("\"", "\\\"")).append("\"") }
        }
        Log.v(TAG, "runScriptSet: Exec result [${runShellCommandGetOutput(command)}]")
    }
}

/**
 * The sync conflict files in [absPath], relative to it. Called by RestApi after a folder completed.
 */
fun getSyncConflictFiles(absPath: String): Array<String> {
    // Unescaped:
    //  find -type f -name "*\.sync-conflict-[0-9]{8}-[0-9]{6}-[a-zA-Z0-9]{7}*" (written out as
    //  bracket expressions) -not -path "\.\/\.stversions\/*" -print | sed "s~\\.\/~~"
    val digit = "[0-9]"
    val alnum = "[a-zA-Z0-9]"
    val command =
        "cd \"$absPath/\";" +
            "find -type f -name \"*\\.sync-conflict-${digit.repeat(8)}-${digit.repeat(6)}-" +
            "${alnum.repeat(7)}*\" -not -path \"\\.\\/\\${Constants.FOLDER_NAME_STVERSIONS}\\/*\"" +
            " -print | sed \"s~\\\\.\\/~~\""
    val output = runShellCommandGetOutput(command)
    return if (output.isEmpty()) arrayOf() else output.splitLikeJava("\n").toTypedArray()
}
