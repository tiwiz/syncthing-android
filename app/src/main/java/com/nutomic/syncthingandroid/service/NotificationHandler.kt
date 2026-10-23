package com.nutomic.syncthingandroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity

/**
 * The app's notifications: the persistent one that keeps the service in the foreground, crashes, a
 * missing permission, and the consent notifications for devices and folders.
 */
class NotificationHandler(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val persistentChannel: NotificationChannel
    private val persistentChannelWaiting: NotificationChannel
    private val infoChannel: NotificationChannel

    private var lastNotificationText: String? = null
    private var lastStartForegroundService = false
    private var appShutdownInProgress = false

    init {
        persistentChannel =
            NotificationChannel(
                    CHANNEL_PERSISTENT,
                    context.getString(R.string.notifications_persistent_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
                .apply {
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                }
        notificationManager.createNotificationChannel(persistentChannel)

        persistentChannelWaiting =
            NotificationChannel(
                    CHANNEL_PERSISTENT_WAITING,
                    context.getString(R.string.notification_persistent_waiting_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
                .apply {
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                }
        // Like the Java original, this sets the persistent channel again, not the waiting one.
        persistentChannel.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        notificationManager.createNotificationChannel(persistentChannelWaiting)

        infoChannel =
            NotificationChannel(
                    CHANNEL_INFO,
                    context.getString(R.string.notifications_other_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(true)
                }
        notificationManager.createNotificationChannel(infoChannel)
    }

    private fun getNotificationBuilder(channel: NotificationChannel): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel.id)

    /** Shows, updates or hides the notification, keeping the previous details. */
    fun updatePersistentNotification(service: SyncthingService) {
        updatePersistentNotification(service, true, 0, 0)
    }

    /** Shows, updates or hides the notification. */
    fun updatePersistentNotification(
        service: SyncthingService,
        persistNotificationDetails: Boolean,
        onlineDeviceCount: Int,
        totalSyncCompletion: Int,
    ) {
        val currentServiceState = service.currentState
        val syncthingRunning =
            currentServiceState == SyncthingService.State.ACTIVE ||
                currentServiceState == SyncthingService.State.STARTING
        // Always in the foreground until shutdown, so the app is not killed and keeps receiving
        // run condition broadcasts (https://stackoverflow.com/a/44505719/1837158).
        val startForegroundService = !appShutdownInProgress

        // Check if we have to stopForeground.
        if (startForegroundService != lastStartForegroundService && !startForegroundService) {
            Log.v(TAG, "Stopping foreground service")
            @Suppress("DEPRECATION") service.stopForeground(false)
        }

        val text =
            persistentText(
                currentServiceState,
                persistNotificationDetails,
                onlineDeviceCount,
                totalSyncCompletion,
            )
        // Reason for two separate IDs: if one of the notification channels is hidden then the
        // startForeground() below won't update the notification but use the old one.
        val idToShow = if (syncthingRunning) ID_PERSISTENT else ID_PERSISTENT_WAITING
        val idToCancel = if (syncthingRunning) ID_PERSISTENT_WAITING else ID_PERSISTENT
        val exitPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_EXIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            getNotificationBuilder(
                    if (syncthingRunning) persistentChannel else persistentChannelWaiting
                )
                .setContentTitle(text)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .addAction(
                    R.drawable.baseline_close_24,
                    context.getString(R.string.exit),
                    exitPendingIntent,
                )
        when {
            appShutdownInProgress -> notificationManager.cancel(idToShow)
            startForegroundService -> {
                Log.v(TAG, "Starting foreground service or updating notification")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(
                        idToShow,
                        builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    service.startForeground(idToShow, builder.build())
                }
            }
            else -> {
                Log.v(TAG, "Updating notification")
                notificationManager.notify(idToShow, builder.build())
            }
        }
        notificationManager.cancel(idToCancel)
        // Remember last notification visibility.
        lastStartForegroundService = startForegroundService
    }

    private fun persistentText(
        state: SyncthingService.State,
        persistNotificationDetails: Boolean,
        onlineDeviceCount: Int,
        totalSyncCompletion: Int,
    ): String =
        when (state) {
            SyncthingService.State.DISABLED -> context.getString(R.string.syncthing_disabled)
            SyncthingService.State.STARTING -> context.getString(R.string.syncthing_starting)
            SyncthingService.State.ACTIVE -> {
                if (lastNotificationText == null || !persistNotificationDetails) {
                    lastNotificationText =
                        activeText(context, onlineDeviceCount, totalSyncCompletion)
                }
                checkNotNull(lastNotificationText)
            }
            else -> context.getString(R.string.syncthing_terminated)
        }

    /** Called by SyncthingService onCreate and onDestroy to indicate app startup and shutdown. */
    fun setAppShutdownInProgress(newValue: Boolean) {
        appShutdownInProgress = newValue
    }

    fun showCrashedNotification(@StringRes title: Int, extraInfo: String?) {
        val n =
            getNotificationBuilder(infoChannel)
                .setContentTitle(context.getString(title, extraInfo))
                .setContentText(context.getString(R.string.notification_crash_text, extraInfo))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, LogActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setAutoCancel(true)
                .build()
        notificationManager.notify(ID_CRASH, n)
    }

    /**
     * A deterministic ID for consent notifications from EventProcessor, so the same device or
     * folder reuses its notification. Between 1000 and 1999, away from the fixed IDs.
     */
    fun getNotificationIdFromText(text: String): Int =
        CONSENT_ID_BASE + Math.floorMod(text.hashCode(), CONSENT_ID_BASE)

    /** Closes a notification. Required after the user hit an action button. */
    fun cancelConsentNotification(notificationId: Int) {
        if (notificationId == 0) {
            return
        }
        Log.v(TAG, "Cancelling notification with id $notificationId")
        notificationManager.cancel(notificationId)
    }

    /** Used by EventProcessor. */
    fun showConsentNotification(
        notificationId: Int,
        text: String?,
        piAccept: PendingIntent?,
        piIgnore: PendingIntent?,
    ) {
        // As we know the id for a specific notification text, dismiss this notification as it may
        // be outdated. This is also valid if the notification does not exist.
        notificationManager.cancel(notificationId)
        val n =
            getNotificationBuilder(infoChannel)
                .setContentTitle(context.getString(R.string.fork_app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(piAccept)
                .addAction(R.drawable.ic_stat_notify, context.getString(R.string.accept), piAccept)
                .addAction(R.drawable.ic_stat_notify, context.getString(R.string.ignore), piIgnore)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setAutoCancel(true)
                .build()
        notificationManager.notify(notificationId, n)
    }

    fun showStoragePermissionRevokedNotification() {
        val n =
            getNotificationBuilder(infoChannel)
                .setContentTitle(context.getString(R.string.syncthing_terminated))
                .setContentText(context.getString(R.string.toast_write_storage_permission_required))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, OnboardingActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        notificationManager.notify(ID_MISSING_PERM, n)
    }

    fun cancelRestartNotification() {
        notificationManager.cancel(ID_RESTART)
    }

    fun showDeviceConnectNotification(
        deviceId: String?,
        deviceName: String,
        deviceAddress: String?,
    ) {
        if (deviceId == null) {
            Log.e(TAG, "showDeviceConnectNotification: deviceId == null")
            return
        }
        val title =
            context.getString(
                R.string.device_rejected,
                deviceName.ifEmpty { deviceId.substring(0, SHORT_ID_LENGTH) },
            )
        val notificationId = getNotificationIdFromText(title)
        // Prepare "accept" action.
        val intentAccept =
            Intent(context, DeviceActivity::class.java)
                .putExtra(DeviceActivity.EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(DeviceActivity.EXTRA_IS_CREATE, true)
                .putExtra(DeviceActivity.EXTRA_DEVICE_ID, deviceId)
                .putExtra(DeviceActivity.EXTRA_DEVICE_NAME, deviceName)
        // Prepare "ignore" action.
        val intentIgnore =
            Intent(context, SyncthingService::class.java)
                .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
                .putExtra(SyncthingService.EXTRA_DEVICE_NAME, deviceName)
                .putExtra(SyncthingService.EXTRA_DEVICE_ADDRESS, deviceAddress)
                .setAction(SyncthingService.ACTION_IGNORE_DEVICE)
        showConsentNotification(
            notificationId,
            title,
            consentAcceptIntent(context, notificationId, intentAccept),
            consentIgnoreIntent(context, notificationId, intentIgnore),
        )
    }

    @Suppress("LongParameterList") // The fields of Syncthing's folder offer.
    fun showFolderShareNotification(
        deviceId: String?,
        deviceName: String?,
        folderId: String?,
        folderLabel: String,
        receiveEncrypted: Boolean,
        remoteEncrypted: Boolean,
        isNewFolder: Boolean,
    ) {
        if (deviceId == null) {
            Log.e(TAG, "showFolderShareNotification: deviceId == null")
            return
        }
        if (folderId == null) {
            Log.e(TAG, "showFolderShareNotification: folderId == null")
            return
        }
        val title =
            context.getString(
                R.string.folder_rejected,
                deviceName,
                if (folderLabel.isEmpty()) folderId else "$folderLabel ($folderId)",
            )
        val notificationId = getNotificationIdFromText(title)
        // Prepare "accept" action.
        val intentAccept =
            Intent(context, FolderActivity::class.java)
                .putExtra(FolderActivity.EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(FolderActivity.EXTRA_IS_CREATE, isNewFolder)
                .putExtra(FolderActivity.EXTRA_DEVICE_ID, deviceId)
                .putExtra(FolderActivity.EXTRA_FOLDER_ID, folderId)
                .putExtra(FolderActivity.EXTRA_FOLDER_LABEL, folderLabel)
                .putExtra(FolderActivity.EXTRA_RECEIVE_ENCRYPTED, receiveEncrypted)
                .putExtra(FolderActivity.EXTRA_REMOTE_ENCRYPTED, remoteEncrypted)
        // Prepare "ignore" action.
        val intentIgnore =
            Intent(context, SyncthingService::class.java)
                .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
                .putExtra(SyncthingService.EXTRA_FOLDER_ID, folderId)
                .putExtra(SyncthingService.EXTRA_FOLDER_LABEL, folderLabel)
                .setAction(SyncthingService.ACTION_IGNORE_FOLDER)
        showConsentNotification(
            notificationId,
            title,
            consentAcceptIntent(context, notificationId, intentAccept),
            consentIgnoreIntent(context, notificationId, intentIgnore),
        )
    }

    private companion object {
        const val TAG = "NotificationHandler"
        const val ID_PERSISTENT = 1
        const val ID_PERSISTENT_WAITING = 4
        const val ID_RESTART = 2
        const val ID_CRASH = 9
        const val ID_MISSING_PERM = 10
        const val CHANNEL_PERSISTENT = "01_syncthing_persistent"
        const val CHANNEL_INFO = "02_syncthing_notifications"
        const val CHANNEL_PERSISTENT_WAITING = "03_syncthing_persistent_waiting"
        const val CONSENT_ID_BASE = 1000
        const val SHORT_ID_LENGTH = 7
    }
}

private const val NOTIFICATION_COMPLETE = 100

/** The persistent notification's text while Syncthing is active. */
private fun activeText(context: Context, onlineDeviceCount: Int, totalSyncCompletion: Int): String =
    when (totalSyncCompletion) {
        -1 ->
            context.getString(
                R.string.syncthing_active_details,
                context.getString(R.string.no_remote_devices_connected),
            )
        NOTIFICATION_COMPLETE ->
            context.getString(
                R.string.syncthing_active_details,
                context.resources.getQuantityString(
                    R.plurals.device_online_up_to_date,
                    onlineDeviceCount,
                    onlineDeviceCount,
                ),
            )
        else ->
            context.resources.getQuantityString(
                R.plurals.syncthing_active_syncing_device_online,
                onlineDeviceCount,
                totalSyncCompletion,
                onlineDeviceCount,
            )
    }

/** Opens the screen that accepts a device or folder. */
private fun consentAcceptIntent(
    context: Context,
    notificationId: Int,
    intent: Intent,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        notificationId,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/**
 * Asks the service to ignore a device or folder. One per notification: PendingIntents that differ
 * only in their extras are the same one.
 */
private fun consentIgnoreIntent(
    context: Context,
    notificationId: Int,
    intent: Intent,
): PendingIntent =
    PendingIntent.getService(
        context,
        notificationId,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
