package com.nutomic.syncthingandroid.service

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.http.PollWebGuiAvailableTask
import com.nutomic.syncthingandroid.util.CertificateValidator
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.PermissionUtil
import com.nutomic.syncthingandroid.util.isTcpPortListening
import com.nutomic.syncthingandroid.util.killProcess
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.inject.Inject

/** Holds the native Syncthing instance and provides an API to access it. */
// One method per intent action, lifecycle step and backup or certificate operation; the file
// handling lives in ConfigBackup.kt and HttpsCertFiles.kt.
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class SyncthingService : Service() {

    fun interface OnServiceStateChangeListener {
        fun onServiceStateChange(currentState: State)
    }

    /** Outcome of [replaceHttpsCertificate] / [resetHttpsCertificate]. */
    enum class HttpsCertReplaceResult {
        /** The new certificate was applied and Syncthing came back online serving it. */
        SUCCESS,

        /** The files were written but Syncthing is not meant to run now; applies on next start. */
        SUCCESS_PENDING_START,

        /** Writing the files failed; the previous certificate was restored. Carries a detail. */
        FAILED,

        /**
         * Syncthing came online but was not serving the new certificate: it could not load the
         * cert/key pair and generated a replacement self-signed certificate instead of failing. The
         * previous certificate has been restored.
         */
        FAILED_CERT_REJECTED,

        /** Syncthing did not come back online at all; the previous certificate was restored. */
        FAILED_NOT_ONLINE,
    }

    fun interface OnHttpsCertReplaceResultListener {
        fun onResult(result: HttpsCertReplaceResult, errorDetail: String?)
    }

    /** Indicates the current state of SyncthingService and of Syncthing itself. */
    enum class State {
        /** Service is initializing, Syncthing was not started yet. */
        INIT,

        /** Syncthing binary is starting. */
        STARTING,

        /**
         * Syncthing binary is running, the REST API is available, and RestApi read the config and
         * is fully initialized.
         */
        ACTIVE,

        /** Syncthing binary is shutting down. */
        DISABLED,

        /** There is some problem that prevents Syncthing from running. */
        ERROR,
    }

    private var enableVerboseLog = false

    /**
     * Starts as DISABLED: RunConditionMonitor sends an update if the binary should run after it got
     * instantiated in [onStartCommand]. Changes go through [onServiceStateChange] under
     * [stateLock].
     */
    var currentState: State = State.DISABLED
        private set

    private lateinit var configRouter: ConfigRouter
    private var config: ConfigXml? = null
    private var syncthingRunnableThread: Thread? = null
    private lateinit var handler: Handler

    private val onServiceStateChangeListeners = HashSet<OnServiceStateChangeListener>()
    private val binder = SyncthingServiceBinder(this)

    private var pollWebGuiAvailableTask: PollWebGuiAvailableTask? = null

    /** The REST API while Syncthing runs. */
    var api: RestApi? = null
        private set

    private var eventProcessor: EventProcessor? = null
    private var runConditionMonitor: RunConditionMonitor? = null
    private var syncthingRunnable: SyncthingRunnable? = null

    @Inject lateinit var notificationHandler: NotificationHandler

    @Inject lateinit var preferences: SharedPreferences

    /**
     * Waits for SyncthingNative to exit after a stop, and resets the database, off the main thread;
     * one job at a time, in order.
     */
    private val lifecycleExecutor = Executors.newSingleThreadExecutor()

    /** The last job on [lifecycleExecutor]; main thread only. */
    private var lifecycleJob: CompletableFuture<*>? = null

    /** Must be locked when changing [currentState]. */
    private val stateLock = Any()

    /** The last decision received by the OnShouldRunChangedListener. */
    private var lastDeterminedShouldRun = false

    /** True if the user granted the storage permission. */
    private var storagePermissionGranted = false

    /** Starts the native binary. */
    override fun onCreate() {
        super.onCreate()
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        logV("onCreate")
        configRouter = ConfigRouter(this)
        handler = Handler(Looper.getMainLooper())
        // If runtime permissions are revoked, Android kills and restarts the service: check again
        // whether we still have the storage permission.
        storagePermissionGranted = PermissionUtil.haveStoragePermission()
        notificationHandler.setAppShutdownInProgress(false)
    }

    /** Handles intent actions, e.g. [ACTION_RESTART]. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!storagePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.")
            notificationHandler.showStoragePermissionRevokedNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        if (runConditionMonitor == null) {
            // Created on the first onStartCommand. After collecting the initial run conditions,
            // it sends its first decision to onShouldRunDecisionChanged.
            runConditionMonitor =
                RunConditionMonitor(
                    this,
                    preferences,
                    ::onShouldRunDecisionChanged,
                    ::applyCustomRunConditions,
                )
        }
        notificationHandler.updatePersistentNotification(this)
        if (intent == null) {
            return START_STICKY
        }
        handleAction(intent)
        return START_STICKY
    }

    private fun handleAction(intent: Intent) {
        when (intent.action) {
            ACTION_RESTART ->
                if (currentState == State.ACTIVE) restart() else afterFreshServiceInstanceStart()
            ACTION_STOP -> onStopAction(intent)
            ACTION_RESET_DATABASE -> onResetDatabaseAction()
            ACTION_RESET_DELTAS -> onResetDeltasAction()
            ACTION_REFRESH_NETWORK_INFO -> runConditionMonitor?.updateShouldRunDecision()
            ACTION_IGNORE_DEVICE,
            ACTION_IGNORE_FOLDER -> onIgnoreAction(intent)
            ACTION_OVERRIDE_CHANGES,
            ACTION_REVERT_LOCAL_CHANGES ->
                if (currentState == State.ACTIVE) onFolderChangesAction(intent)
                else afterFreshServiceInstanceStart()
            else -> afterFreshServiceInstanceStart()
        }
    }

    /** Ignores the device or folder of a consent notification, and dismisses it. */
    private fun onIgnoreAction(intent: Intent) {
        val deviceId = checkNotNull(intent.getStringExtra(EXTRA_DEVICE_ID))
        if (intent.action == ACTION_IGNORE_DEVICE) {
            configRouter.ignoreDevice(
                api,
                deviceId,
                intent.getStringExtra(EXTRA_DEVICE_NAME),
                intent.getStringExtra(EXTRA_DEVICE_ADDRESS),
            )
        } else {
            configRouter.ignoreFolder(
                api,
                deviceId,
                checkNotNull(intent.getStringExtra(EXTRA_FOLDER_ID)),
                intent.getStringExtra(EXTRA_FOLDER_LABEL),
            )
        }
        notificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    /** Overrides remote or reverts local changes of a folder; Syncthing is running. */
    private fun onFolderChangesAction(intent: Intent) {
        val restApi = checkNotNull(api)
        val folderId = checkNotNull(intent.getStringExtra(EXTRA_FOLDER_ID))
        if (intent.action == ACTION_OVERRIDE_CHANGES) {
            restApi.overrideChanges(folderId)
        } else {
            restApi.revertLocalChanges(folderId)
        }
    }

    private fun restart() {
        shutdown(State.INIT)
        launchStartupTask(SyncthingRunnable.Command.MAIN)
    }

    private fun onStopAction(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, false)) {
            // The native binary crashed. Changing the state skips the "defer until Syncthing is
            // started" routine of a clean shutdown: the crashed instance is shut down forcefully.
            currentState = State.ERROR
            shutdownInBackground(State.DISABLED)
        } else if (currentState == State.STARTING || currentState == State.ACTIVE) {
            // Graceful shutdown.
            shutdownInBackground(State.DISABLED)
        }
    }

    /**
     * Stops Syncthing if it runs, resets the database (Syncthing exits after the reset), then
     * starts it again if it was running before.
     */
    private fun onResetDatabaseAction() {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring the database reset until State.STARTING was left")
            handler.postDelayed({ onResetDatabaseAction() }, DEFER_MILLIS)
            return
        }
        Log.i(TAG, "Invoking reset of database")
        val waitForExit =
            if (currentState != State.DISABLED) beginShutdown(State.DISABLED) else null
        val reset =
            SyncthingRunnable(
                this,
                SyncthingRunnable.Command.RESET_DATABASE,
                preferences,
                notificationHandler,
            )
        inBackground({
            waitForExit?.invoke()
            reset.run()
        }) {
            if (lastDeterminedShouldRun) {
                launchStartupTask(SyncthingRunnable.Command.MAIN)
            }
        }
    }

    /**
     * Stops Syncthing if it runs and starts it resetting the delta indexes; it keeps running after
     * the reset. If it was not running before, it is shut down again once it leaves STARTING: then
     * the reset is done and the web UI came up.
     */
    private fun onResetDeltasAction() {
        Log.i(TAG, "Invoking reset of delta indexes")
        if (currentState != State.DISABLED) {
            // Shutdown synchronously.
            shutdown(State.DISABLED)
        }
        launchStartupTask(SyncthingRunnable.Command.RESET_DELTAS)
        if (!lastDeterminedShouldRun) {
            // Shutdown if syncthing was not running before the UI action was raised.
            shutdown(State.DISABLED)
        }
    }

    /**
     * A fresh service start, right after the run condition evaluation: SyncthingNative may be
     * starting in the background meanwhile, or not be there.
     */
    private fun afterFreshServiceInstanceStart() {
        logV(
            "afterFreshServiceInstanceStart: Service started from scratch, SyncthingNative is " +
                "going to STATE_$currentState meanwhilst ..."
        )
        if (currentState == State.DISABLED) {
            // Read and parse the config from disk.
            loadConfigOrStop("afterFreshServiceInstanceStart:OpenConfigException")
        }
    }

    /** Loads config.xml; if it cannot be read, reports it, goes to ERROR and stops the service. */
    private fun loadConfigOrStop(crashExtraInfo: String): ConfigXml? {
        val configXml = ConfigXml(this)
        try {
            configXml.loadConfig()
        } catch (_: ConfigXml.OpenConfigException) {
            notificationHandler.showCrashedNotification(R.string.config_read_failed, crashExtraInfo)
            synchronized(stateLock) { onServiceStateChange(State.ERROR) }
            stopSelf()
            return null
        }
        return configXml
    }

    /**
     * Called by RunConditionMonitor when its decision to run or stop Syncthing changed; starts or
     * stops the binary accordingly.
     */
    private fun onShouldRunDecisionChanged(newShouldRunDecision: Boolean) {
        if (newShouldRunDecision == lastDeterminedShouldRun) {
            return
        }
        Log.i(
            TAG,
            "shouldRun decision changed to $newShouldRunDecision according to configured run conditions.",
        )
        lastDeterminedShouldRun = newShouldRunDecision
        if (newShouldRunDecision) {
            if (currentState == State.DISABLED || currentState == State.INIT) {
                launchStartupTask(SyncthingRunnable.Command.MAIN)
            }
        } else if (currentState != State.DISABLED) {
            shutdownInBackground(State.DISABLED)
        }
    }

    /**
     * After sync preconditions changed, pauses or resumes the folders and devices that have their
     * own sync conditions: through RestApi while Syncthing runs, in config.xml otherwise.
     */
    private fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(stateLock) {
            val restApi = api
            if (restApi != null && currentState == State.ACTIVE) {
                // Forward event because syncthing is running.
                restApi.applyCustomRunConditions(runConditionMonitor)
                return
            }
        }
        val configXml = loadConfigOrStop("applyCustomRunConditions:OpenConfigException") ?: return
        if (applyObjectRunConditions(configXml, preferences, runConditionMonitor, ::logV)) {
            logV("applyCustomRunConditions: Saving changed config ...")
            configXml.saveChanges()
        } else {
            logV("applyCustomRunConditions: No action was necessary.")
        }
    }

    /** Launches the Syncthing binary, then waits for its web GUI to come online. */
    private fun launchStartupTask(srCommand: SyncthingRunnable.Command) {
        synchronized(stateLock) {
            if (currentState != State.DISABLED && currentState != State.INIT) {
                Log.e(TAG, "launchStartupTask: Wrong state $currentState detected. Cancelling.")
                return
            }
        }
        if (afterLifecycleJob { launchStartupTask(srCommand) }) {
            Log.i(TAG, "launchStartupTask: Waiting for the previous SyncthingNative to exit")
            return
        }
        val loadedConfig = loadConfigOrStop("launchStartupTask:OpenConfigException") ?: return
        config = loadedConfig

        // Is the configured web GUI port taken by another app or process?
        val webGuiTcpPort = loadedConfig.webGuiBindPort
        if (isTcpPortListening(webGuiTcpPort)) {
            // Starting SyncthingNative would wait forever for life signs on the port (ANR).
            Log.e(
                TAG,
                "launchStartupTask: WebUI tcp port $webGuiTcpPort unavailable. Second instance?",
            )
            notificationHandler.showCrashedNotification(
                R.string.webui_tcp_port_unavailable,
                webGuiTcpPort.toString(),
            )
            return
        }

        onServiceStateChange(State.STARTING)

        if (api == null) {
            api =
                RestApi(
                    this,
                    notificationHandler,
                    loadedConfig.webGuiUrl,
                    loadedConfig.apiKey,
                    ::onApiAvailable,
                ) {
                    onServiceStateChange(currentState)
                }
            Log.i(TAG, "Web GUI will be available at " + loadedConfig.webGuiUrl)
        }

        // One binary at a time.
        if (syncthingRunnable != null || syncthingRunnableThread != null) {
            Log.e(TAG, "onStartupTaskCompleteListener: Syncthing binary lifecycle violated")
            return
        }
        val runnable = SyncthingRunnable(this, srCommand, preferences, notificationHandler)
        syncthingRunnable = runnable

        // An old instance still runs after an in-place app upgrade: end it.
        killProcess(Constants.FILENAME_SYNCTHING_BINARY)

        // Start the syncthing binary in a separate thread.
        syncthingRunnableThread =
            Thread(runnable).apply {
                setUncaughtExceptionHandler { _, _ ->
                    Log.e(
                        TAG,
                        "mSyncthingRunnableThread: Uncaught exception [ExecutableNotFoundException]",
                    )
                    notificationHandler.showCrashedNotification(
                        R.string.executable_not_found,
                        Constants.FILENAME_SYNCTHING_BINARY,
                    )
                }
                start()
            }

        // Wait for the web GUI of the binary to come online. Another thread could ask to stop the
        // binary meanwhile; see onDestroy.
        if (pollWebGuiAvailableTask == null) {
            pollWebGuiAvailableTask =
                PollWebGuiAvailableTask(this, loadedConfig.webGuiUrl, loadedConfig.apiKey) {
                    Log.i(TAG, "Web GUI has come online at " + loadedConfig.webGuiUrl)
                    api?.readConfigFromRestApi()
                }
        }
    }

    /**
     * Called when RestApi is fully initialized. Stressing the UI can stop the binary meanwhile, so
     * the API may be gone.
     */
    private fun onApiAvailable() {
        val restApi = api
        if (restApi == null) {
            Log.e(TAG, "onApiAvailable: Did we stop the binary during startup? mRestApi == null")
            return
        }
        synchronized(stateLock) {
            if (currentState != State.STARTING) {
                Log.e(
                    TAG,
                    "onApiAvailable: Wrong state $currentState detected. Cancelling callback.",
                )
                return
            }
            onServiceStateChange(State.ACTIVE)
        }
        if (eventProcessor == null) {
            eventProcessor =
                EventProcessor(this, restApi, preferences, notificationHandler).also { it.start() }
        }
    }

    override fun onBind(intent: Intent?): SyncthingServiceBinder = binder

    /** Stops the native binary and the RunConditionMonitor. */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // No run condition events during shutdown.
        runConditionMonitor?.shutdown()
        notificationHandler.setAppShutdownInProgress(true)
        if (!storagePermissionGranted) {
            // Without the storage permission the binary did not start: we are in INIT and shut
            // this service down right away.
            Log.i(TAG, "Shutting down syncthing binary due to missing storage permission.")
        }
        shutdown(State.DISABLED)
        lifecycleExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Stops SyncthingNative and its helpers (event processor, API) synchronously and moves to
     * [newState]; waits while the binary is STARTING.
     */
    private fun shutdown(newState: State) {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left")
            handler.postDelayed({ shutdown(newState) }, DEFER_MILLIS)
            return
        }
        val waitForExit = beginShutdown(newState)
        try {
            lifecycleJob?.get()
        } catch (e: ExecutionException) {
            Log.w(TAG, "shutdown: The previous lifecycle job failed", e)
        } catch (_: InterruptedException) {
            Log.w(TAG, "shutdown: Interrupted waiting for the previous lifecycle job")
        }
        waitForExit()
    }

    /**
     * Like [shutdown], but waits for SyncthingNative to exit off the main thread. Starting again
     * waits until it has exited.
     */
    private fun shutdownInBackground(newState: State) {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left")
            handler.postDelayed({ shutdownInBackground(newState) }, DEFER_MILLIS)
            return
        }
        inBackground(beginShutdown(newState))
    }

    /**
     * Moves to [newState] and stops the helpers (event processor, API); returns the slow part,
     * which asks SyncthingNative to exit and waits until it did.
     */
    private fun beginShutdown(newState: State): () -> Unit {
        synchronized(stateLock) { onServiceStateChange(newState) }

        pollWebGuiAvailableTask?.cancelRequestsAndCallback()
        pollWebGuiAvailableTask = null

        eventProcessor?.stop()
        eventProcessor = null

        notificationHandler.cancelRestartNotification()

        val running = syncthingRunnable != null
        val restApi = api?.takeIf { running }
        api = null
        val thread = syncthingRunnableThread.takeIf { running }
        if (running) {
            syncthingRunnableThread = null
            syncthingRunnable = null
        }
        return {
            restApi?.shutdown()
            if (running) {
                killProcess(Constants.FILENAME_SYNCTHING_BINARY)
                thread?.let {
                    logV(
                        "Waiting for mSyncthingRunnableThread to finish after killProcess(Syncthing) ..."
                    )
                    try {
                        it.join()
                    } catch (_: InterruptedException) {
                        Log.w(TAG, "mSyncthingRunnableThread InterruptedException")
                    }
                    Log.d(TAG, "Finished mSyncthingRunnableThread.")
                }
            }
        }
    }

    /**
     * Runs [work] on [lifecycleExecutor] after the earlier jobs, then [then] on the main thread.
     */
    private fun inBackground(work: () -> Unit, then: () -> Unit = {}) {
        lifecycleJob =
            CompletableFuture.runAsync(work, lifecycleExecutor).whenComplete { _, error ->
                error?.let { Log.e(TAG, "A lifecycle job failed", it) }
                handler.post(then)
            }
    }

    /** Runs [action] on the main thread once the last lifecycle job is done; false if it is. */
    private fun afterLifecycleJob(action: () -> Unit): Boolean {
        val job = lifecycleJob?.takeUnless { it.isDone } ?: return false
        job.whenComplete { _, _ -> handler.post(action) }
        return true
    }

    /** Re-evaluates the run conditions now, e.g. after the settings changed. */
    fun evaluateRunConditions() {
        val monitor = runConditionMonitor ?: return
        Log.d(TAG, "Forced re-evaluating run conditions ...")
        monitor.updateShouldRunDecision()
    }

    /**
     * Registers a listener for the service state. It is called right away with the current state,
     * then whenever the state changes, always on the main thread.
     *
     * @see unregisterOnServiceStateChangeListener
     */
    fun registerOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        // Send the current state first, or the subscriber knows nothing until the next change.
        listener.onServiceStateChange(currentState)
        onServiceStateChangeListeners.add(listener)
    }

    /** @see registerOnServiceStateChangeListener */
    fun unregisterOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        onServiceStateChangeListeners.remove(listener)
    }

    /** Moves to [newState] and tells the listeners on the main thread. */
    private fun onServiceStateChange(newState: State) {
        if (newState == currentState) {
            Log.d(TAG, "onServiceStateChange: Called with unchanged state $newState")
            return
        }
        Log.i(TAG, "onServiceStateChange: from $currentState to $newState")
        currentState = newState
        handler.post {
            notificationHandler.updatePersistentNotification(this)
            // A copy: a listener may unregister itself or others while being called.
            for (listener in onServiceStateChangeListeners.toList()) {
                if (listener in onServiceStateChangeListeners) {
                    listener.onServiceStateChange(currentState)
                }
            }
        }
    }

    val runDecisionExplanation: String
        get() =
            runConditionMonitor?.runDecisionExplanation
                ?: resources.getString(R.string.reason_run_condition_monitor_not_instantiated)

    /** The backup zip; by default /storage/emulated/0/backups/syncthing/config.zip. */
    private val backupZipFile: File
        get() {
            // Empty when the user clears the field in the settings, or after an import by older
            // versions (importConfigSharedPrefs restored an unset pref as ""). An empty path would
            // crash.
            val relPathToZip =
                preferences
                    .getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, DEFAULT_BACKUP_PATH)
                    .orEmpty()
                    .ifEmpty { DEFAULT_BACKUP_PATH }
            return File(Environment.getExternalStorageDirectory(), relPathToZip)
        }

    /**
     * Exports the config, keys, index database and preferences to the backup zip. Stops Syncthing
     * for it and starts it again afterwards if the run conditions say so. [onDone] gets whether the
     * export worked, on the main thread.
     *
     * To test on an emulator: adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo
     * === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     */
    fun exportConfig(onDone: (Boolean) -> Unit) {
        handler.post {
            Log.d(TAG, "exportConfig BEGIN")
            val zipFile = backupZipFile
            whileStopped({ exportBackup(this, preferences, zipFile) }) { success ->
                Log.d(TAG, "exportConfig END")
                onDone(success)
            }
        }
    }

    /**
     * Imports the config, keys and preferences from the backup zip, replacing the index database.
     * Stops Syncthing for it and starts it again afterwards if the run conditions say so. [onDone]
     * gets whether the import worked (false e.g. if files aren't found), on the main thread.
     */
    fun importConfig(onDone: (Boolean) -> Unit) {
        handler.post {
            val zipFilePath = backupZipFile
            // If the user set one, decrypt the zip file with a password.
            val zipEncryptionPassword =
                preferences.getString(Constants.PREF_BACKUP_PASSWORD, "").orEmpty()
            CompletableFuture.supplyAsync(
                    { openBackupZip(zipFilePath, zipEncryptionPassword, cacheDir) },
                    lifecycleExecutor,
                )
                .whenComplete { zipFile, _ ->
                    handler.post {
                        if (zipFile == null) {
                            onDone(false)
                        } else {
                            Log.d(TAG, "importConfig BEGIN")
                            whileStopped(
                                { restoreBackup(this, preferences, zipFile, ::logV) },
                                onDone,
                            )
                        }
                    }
                }
        }
    }

    /**
     * Stops Syncthing, waiting while it is STARTING, and runs [work] off the main thread once it
     * has exited. Then starts Syncthing again if the run conditions say so and passes the result of
     * [work] to [onDone]. Main thread only, like [onResetDatabaseAction].
     */
    private fun whileStopped(work: () -> Boolean, onDone: (Boolean) -> Unit) {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring the backup until State.STARTING was left")
            handler.postDelayed({ whileStopped(work, onDone) }, DEFER_MILLIS)
            return
        }
        val waitForExit =
            if (currentState != State.DISABLED) beginShutdown(State.DISABLED) else null
        var success = false
        inBackground({
            waitForExit?.invoke()
            success = work()
        }) {
            onDone(success)
            if (lastDeterminedShouldRun) {
                launchStartupTask(SyncthingRunnable.Command.MAIN)
            }
        }
    }

    /**
     * Replaces the web GUI HTTPS certificate and key with the given PEM bytes, then restarts
     * Syncthing so the new files take effect. If the restart fails to bring the web GUI back
     * online, the previous certificate and key are restored.
     *
     * The bytes are expected to be validated already (see [CertificateValidator]). The whole
     * start/stop lifecycle runs on the main thread, the only one where the binary orchestration
     * fields are safe to touch.
     */
    fun replaceHttpsCertificate(
        certPem: ByteArray,
        keyPem: ByteArray,
        listener: OnHttpsCertReplaceResultListener,
    ) {
        handler.post { doReplaceHttpsCertificate(certPem, keyPem, listener) }
    }

    /**
     * Deletes the user-supplied HTTPS certificate/key so Syncthing generates a fresh self-signed
     * certificate at the next start, then restarts (with rollback on failure).
     */
    fun resetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        handler.post { doResetHttpsCertificate(listener) }
    }

    private fun doReplaceHttpsCertificate(
        certPem: ByteArray,
        keyPem: ByteArray,
        listener: OnHttpsCertReplaceResultListener,
    ) {
        // shutdown() defers while STARTING; wait it out so our file writes don't race the binary.
        if (currentState == State.STARTING) {
            handler.postDelayed(
                { doReplaceHttpsCertificate(certPem, keyPem, listener) },
                DEFER_MILLIS,
            )
            return
        }
        val certFile = Constants.getHttpsCertFile(this)
        val keyFile = Constants.getHttpsKeyFile(this)
        // Stop the binary so it releases the cert/key before we overwrite them.
        if (currentState != State.DISABLED) {
            shutdown(State.DISABLED)
        }
        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)
        try {
            writeBytesAtomic(certFile, certPem)
            writeBytesAtomic(keyFile, keyPem)
            restrictToOwner(keyFile)
        } catch (e: IOException) {
            Log.e(TAG, "doReplaceHttpsCertificate: Failed to write new cert/key", e)
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
            if (lastDeterminedShouldRun) {
                launchStartupTask(SyncthingRunnable.Command.MAIN)
            }
            listener.onResult(HttpsCertReplaceResult.FAILED, e.message)
            return
        }
        // Captured before the restart, to tell afterwards whether Syncthing serves what we wrote
        // or replaced it with a self-signed certificate of its own.
        val expectedFingerprint = CertificateValidator.leafFingerprint(certPem)
        applyCertChangeWithVerify(
            CertChange(certFile, keyFile, certBak, keyBak, expectedFingerprint, listener)
        )
    }

    private fun doResetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        if (currentState == State.STARTING) {
            handler.postDelayed({ doResetHttpsCertificate(listener) }, DEFER_MILLIS)
            return
        }
        val certFile = Constants.getHttpsCertFile(this)
        val keyFile = Constants.getHttpsKeyFile(this)
        if (currentState != State.DISABLED) {
            shutdown(State.DISABLED)
        }
        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)
        // Removing the files makes Syncthing generate a fresh self-signed certificate at startup.
        deleteQuietly(certFile)
        deleteQuietly(keyFile)
        // No expected fingerprint: regenerating is the whole point, so any certificate Syncthing
        // comes up with is the right one.
        applyCertChangeWithVerify(CertChange(certFile, keyFile, certBak, keyBak, null, listener))
    }

    /** A certificate change on disk, with the backups to roll back to. */
    private class CertChange(
        val certFile: File,
        val keyFile: File,
        val certBak: File?,
        val keyBak: File?,
        val expectedFingerprint: String?,
        val listener: OnHttpsCertReplaceResultListener,
    ) {
        fun restore() {
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
        }

        fun dropBackups() {
            deleteQuietly(certBak)
            deleteQuietly(keyBak)
        }
    }

    private fun applyCertChangeWithVerify(change: CertChange) {
        if (lastDeterminedShouldRun) {
            CertChangeVerification(change).start()
        } else {
            // Not meant to run now; the new files take effect on the next start.
            change.dropBackups()
            change.listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null)
        }
    }

    /**
     * Restarts the binary and watches the service state: failure on ERROR, on an abnormal STARTING
     * → DISABLED transition (crashed binary) or on a watchdog timeout.
     *
     * Reaching ACTIVE is necessary but **not sufficient**. When Syncthing cannot load the cert/key
     * pair it does not fail: it generates a fresh self-signed certificate, overwrites the files,
     * and starts normally (see `lib/api/api.go` `getListener`). So success also requires the
     * certificate on disk to still be the one we wrote, identified by the expected fingerprint.
     *
     * On any failure the backed-up cert/key are restored and a known-good instance is brought back
     * up.
     */
    private inner class CertChangeVerification(private val change: CertChange) :
        OnServiceStateChangeListener {
        private var resolved = false
        private var sawStarting = false
        private var failureReason = HttpsCertReplaceResult.FAILED_NOT_ONLINE

        private val watchdog = Runnable {
            if (resolved) return@Runnable
            resolved = true
            unregisterOnServiceStateChangeListener(this)
            if (currentState == State.ACTIVE) finishSuccess() else finishFailure()
        }

        fun start() {
            // Registering replays the current state (DISABLED) synchronously; that is ignored
            // because sawStarting is still false.
            registerOnServiceStateChangeListener(this)
            handler.postDelayed(watchdog, HTTPS_CERT_VERIFY_TIMEOUT_MS)
            launchStartupTask(SyncthingRunnable.Command.MAIN)
        }

        override fun onServiceStateChange(currentState: State) {
            if (resolved) return
            if (currentState == State.STARTING) {
                sawStarting = true
                return
            }
            val success = currentState == State.ACTIVE
            val failure =
                currentState == State.ERROR || (sawStarting && currentState == State.DISABLED)
            if (!success && !failure) return
            resolved = true
            handler.removeCallbacks(watchdog)
            // Unregister and restart outside onServiceStateChange's listener iteration.
            handler.post {
                unregisterOnServiceStateChangeListener(this)
                if (success) finishSuccess() else finishFailure()
            }
        }

        private fun finishFailure() {
            // Stop first, then restore. In the FAILED_CERT_REJECTED case Syncthing is running and
            // serving a certificate that differs from the file on disk; restoring the file under a
            // live instance would leave ApiRequest pinning a certificate that is not the one being
            // served, so any request in that window fails.
            if (
                this@SyncthingService.currentState != State.DISABLED &&
                    this@SyncthingService.currentState != State.INIT
            ) {
                shutdown(State.INIT)
            }
            change.restore()
            // Bring the previous, known-good certificate back online.
            launchStartupTask(SyncthingRunnable.Command.MAIN)
            change.listener.onResult(failureReason, null)
        }

        private fun finishSuccess() {
            val expected = change.expectedFingerprint
            if (expected != null && !certFileHasFingerprint(change.certFile, expected)) {
                Log.w(
                    TAG,
                    "verifyRestartAndRollback: Syncthing replaced the supplied certificate " +
                        "with a generated one; rolling back.",
                )
                failureReason = HttpsCertReplaceResult.FAILED_CERT_REJECTED
                finishFailure()
                return
            }
            change.dropBackups()
            change.listener.onResult(HttpsCertReplaceResult.SUCCESS, null)
        }
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "SyncthingService"

        /** Intent action to perform a Syncthing restart. */
        const val ACTION_RESTART = ".SyncthingService.RESTART"

        /** Intent action to perform a Syncthing stop. */
        const val ACTION_STOP = ".SyncthingService.STOP"

        /** Intent action to reset Syncthing's database. */
        const val ACTION_RESET_DATABASE = ".SyncthingService.RESET_DATABASE"

        /** Intent action to reset Syncthing's delta indexes. */
        const val ACTION_RESET_DELTAS = ".SyncthingService.RESET_DELTAS"

        const val ACTION_REFRESH_NETWORK_INFO = ".SyncthingService.REFRESH_NETWORK_INFO"

        /** Intent action to permanently ignore a device connection request. */
        const val ACTION_IGNORE_DEVICE = ".SyncthingService.IGNORE_DEVICE"

        /** Intent action to permanently ignore a folder share request. */
        const val ACTION_IGNORE_FOLDER = ".SyncthingService.IGNORE_FOLDER"

        /** Intent action to override folder changes. */
        const val ACTION_OVERRIDE_CHANGES = ".SyncthingService.OVERRIDE_CHANGES"

        /** Intent action to revert local folder changes. */
        const val ACTION_REVERT_LOCAL_CHANGES = ".SyncthingService.REVERT_LOCAL_CHANGES"

        /** Extra used together with ACTION_IGNORE_DEVICE, ACTION_IGNORE_FOLDER. */
        const val EXTRA_NOTIFICATION_ID = ".SyncthingService.EXTRA_NOTIFICATION_ID"

        /** Extra used together with ACTION_IGNORE_DEVICE. */
        const val EXTRA_DEVICE_ID = ".SyncthingService.EXTRA_DEVICE_ID"

        /** Extra used together with ACTION_IGNORE_DEVICE. */
        const val EXTRA_DEVICE_ADDRESS = ".SyncthingService.EXTRA_DEVICE_ADDRESS"

        /** Extra used together with ACTION_IGNORE_DEVICE. */
        const val EXTRA_DEVICE_NAME = ".SyncthingService.EXTRA_DEVICE_NAME"

        /** Extra used together with ACTION_IGNORE_FOLDER. */
        const val EXTRA_FOLDER_ID = ".SyncthingService.EXTRA_FOLDER_ID"

        /** Extra used together with ACTION_IGNORE_FOLDER. */
        const val EXTRA_FOLDER_LABEL = ".SyncthingService.EXTRA_FOLDER_LABEL"

        /** Extra used together with ACTION_STOP. */
        const val EXTRA_STOP_AFTER_CRASHED_NATIVE =
            ".SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE"

        /**
         * Backstop timeout for the certificate change verification, in case the state machine never
         * reaches a terminal state (e.g. the binary crashed via a path that doesn't move to ERROR).
         */
        private const val HTTPS_CERT_VERIFY_TIMEOUT_MS = 30_000L

        /** How long shutdown and certificate changes wait while Syncthing is STARTING. */
        private const val DEFER_MILLIS = 1000L

        private const val DEFAULT_BACKUP_PATH = "backups/syncthing/config.zip"

        /** [c] as a set of [type], casting every element; null for null. */
        @JvmStatic
        fun <T> asSet(c: Set<*>?, type: Class<out T>): Set<T>? =
            c?.mapTo(HashSet()) { type.cast(it) }
    }
}
