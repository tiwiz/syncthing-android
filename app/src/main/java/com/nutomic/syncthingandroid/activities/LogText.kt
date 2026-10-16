package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.util.Log
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.runShellCommandGetOutput
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Android log as the log screen shows it, and the log files it reads and shares.

private const val TAG = "LogActivity"

private const val ANDROID_LOG_FILE_MAX_LINES = 2000

/** Both logs, as the screen shows them. */
internal class Logs(val android: String, val syncthing: String)

/**
 * Reads the Android log from logcat, filtered and written to its file for sharing, and Syncthing's
 * log from its file.
 */
internal suspend fun readLogs(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Logs =
    withContext(ioDispatcher) {
        val android =
            filterAndroidLog(
                runShellCommandGetOutput(
                    "/system/bin/logcat -t $ANDROID_LOG_FILE_MAX_LINES -v time *:i ps:s art:s"
                )
            )
        writeLogFile(Constants.getAndroidLogFile(context), android)
        Logs(android, readLogFile(Constants.getSyncthingLogFile(context)))
    }

/** Log lines from components that only add noise to a Syncthing bug report. */
private val NOISE =
    listOf(
        "--- beginning of ",
        "/AbsListViewStubImpl",
        "W/ActionBarDrawerToggle",
        "/ActivityThread",
        "/Adreno",
        "/AdrenoUtils",
        "/AiRecognition",
        "/androidtc",
        "/AssistStructure",
        "/AudioCapabilities",
        "/BLASTBufferQueue_Java",
        "/BinderMonitor",
        "I/chatty",
        "/CameraExtImplXiaoMi",
        "/CameraManagerGlobal",
        "/Choreographer",
        "W/chmod",
        "/chromium",
        "/ContentCaptureHelper",
        "/ContentCatcher",
        "/cr_AwContents",
        "/cr_base",
        "/cr_BrowserStartup",
        "/cr_CachingUmaRecorder",
        "/cr_ChildProcessConn",
        "/cr_ChildProcLH",
        "/cr_CombinedPProvider",
        "/cr_CrashFileManager",
        "/cr_LibraryLoader",
        "/cr_media",
        "/cr_MediaCodecUtil",
        "/cr_PolicyProvider",
        "/cr_VAUtil",
        "/cr_WVCFactoryProvider",
        "I/ConfigStore",
        "/dalvikvm",
        "/DecorView",
        "/DpmTcmClient",
        "/EditorStubImpl",
        "/EGL",
        "/EpFrameworkFactory",
        "/eglCodecCommon",
        "/FeatureParser",
        "/FenceTime",
        "E/FileUtils err",
        "/FloatingActionMode",
        "/ForceDarkHelperStubImpl",
        "/FrameEvents",
        "/FramePredict",
        "/FrameTracker",
        "/Gralloc",
        "/HandWritingStubImpl",
        "/HWUI",
        "/IInputConnectionWrapper",
        "/ImeTracker",
        "/ImeBackDispatcher",
        "/ImeFocusController",
        "/InputEventReceiver",
        "/InputMethodManager",
        "/InsetsController",
        "/InsetsSourceConsumer",
        "/JavaheapMonitor",
        "E/LB",
        "W/Looper",
        "/libEGL",
        "/libMiGL",
        "E/libc",
        "W/libc",
        "/MessageMonitor",
        "/MIUI",
        "/MiInputConsumer",
        "/Miui",
        "MiuiBoosterUtils",
        "/NativeTurboSchedManager",
        "W/netstat",
        "/ngandroid.debu",
        "/OpenGLRenderer",
        "/PacProxySelector",
        "/Perf",
        "/re-initialized",
        "/RemoteInputConnectionImpl",
        "/RenderInspector",
        "/RenderThread",
        "/ResourceType",
        "W/sh",
        "W/Settings",
        "/SplineOverScroller",
        "/StrictMode",
        "/studio.deploy",
        "/SurfaceComposerClient",
        "/SurfaceSyncGroup",
        "I/System.out",
        "W/TextView",
        "I/Timeline",
        "I/VRI",
        "/VideoCapabilities",
        "/ViewRootImpl",
        "I/WebViewFactory",
        "WindowOnBackDispatcher",
        "I/X509Util",
        "/ziparchive",
        "/zygote",
        "/zygote64",
    )

private val PID = Regex("\\(\\s?[0-9]+\\):")
private val DATE = Regex("^[0-9]{2}-[0-9]{2}\\s")

// With the spaces after them: the native code's lines have two, once its tag is gone.
private val MILLISECONDS = Regex("^([0-9]{2}:[0-9]{2}:[0-9]{2})\\.[0-9]{3}\\s+")

/**
 * The logcat output without other components' noise, the native code's tag, process ids, dates and
 * milliseconds.
 */
internal fun filterAndroidLog(logcat: String): String =
    logcat
        .replace("I/SyncthingNativeCode", "")
        .replace(PID, "")
        .split("\n")
        .filter { line -> NOISE.none { line.contains(it) } }
        .joinToString("\n") { it.replaceFirst(DATE, "").replaceFirst(MILLISECONDS, "$1 ") }

/** The file's text, or "" when it is missing or unreadable. */
internal fun readLogFile(file: File): String {
    if (!file.exists()) {
        Log.e(TAG, "readLogFile: File missing '$file'")
        return ""
    }
    return try {
        file.readText()
    } catch (e: IOException) {
        Log.e(TAG, "readLogFile: Failed to read '$file' #1", e)
        ""
    }
}

internal fun writeLogFile(file: File, logContent: String) {
    try {
        file.writeText(logContent)
    } catch (e: IOException) {
        Log.w(TAG, "writeLogFile: Failed to write '$file' #1", e)
    }
}
