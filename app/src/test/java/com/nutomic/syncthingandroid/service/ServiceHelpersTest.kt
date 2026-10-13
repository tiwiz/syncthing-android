package com.nutomic.syncthingandroid.service

import android.app.job.JobParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.PersistableBundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** The sync trigger job and the registry of dynamically registered receivers. */
@RunWith(AndroidJUnit4::class)
class ServiceHelpersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ReceiverManager.unregisterAllReceivers(context)
    }

    private fun jobParameters(extras: PersistableBundle): JobParameters =
        Shadow.newInstanceOf(JobParameters::class.java).also {
            ReflectionHelpers.setField(it, "extras", extras)
        }

    /** Runs the job with [extras] and returns the trigger broadcasts it sent. */
    private fun runJob(extras: PersistableBundle): List<Intent> {
        val received = mutableListOf<Intent>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    received += intent
                }
            }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED))
        val service = Robolectric.buildService(SyncTriggerJobService::class.java).create().get()

        assertTrue(service.onStartJob(jobParameters(extras)))
        shadowOf(Looper.getMainLooper()).idle()
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        return received
    }

    @Test
    fun `a start job asks to begin the active time window`() {
        val extras =
            PersistableBundle().apply {
                putInt(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 1)
            }

        val intent = runJob(extras).single()

        assertTrue(
            intent.getBooleanExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, false)
        )
    }

    @Test
    fun `a stop job triggers without the flag`() {
        val intent = runJob(PersistableBundle()).single()

        assertFalse(intent.hasExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW))
    }

    @Test
    fun `a stopped job is rescheduled`() {
        val service = Robolectric.buildService(SyncTriggerJobService::class.java).create().get()

        assertTrue(service.onStopJob(jobParameters(PersistableBundle())))
    }

    @Test
    fun `registered receivers get broadcasts until all are unregistered`() {
        var count = 0
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    count++
                }
            }

        ReceiverManager.registerReceiver(context, receiver, IntentFilter(ACTION))
        assertTrue(ReceiverManager.isReceiverRegistered(receiver))
        context.sendBroadcast(Intent(ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, count)

        ReceiverManager.unregisterAllReceivers(context)
        context.sendBroadcast(Intent(ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, count)
        assertFalse(ReceiverManager.isReceiverRegistered(receiver))
    }

    @Test
    fun `a receiver Android already dropped is forgotten without an error`() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = Unit
            }
        ReceiverManager.registerReceiver(context, receiver, IntentFilter(ACTION))
        context.unregisterReceiver(receiver)

        ReceiverManager.unregisterAllReceivers(context)

        assertFalse(ReceiverManager.isReceiverRegistered(receiver))
    }

    @Test
    fun `unregistering without a context keeps the receivers`() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = Unit
            }
        ReceiverManager.registerReceiver(context, receiver, IntentFilter(ACTION))

        ReceiverManager.unregisterAllReceivers(null)

        assertTrue(ReceiverManager.isReceiverRegistered(receiver))
    }

    private companion object {
        const val ACTION = "com.nutomic.syncthingandroid.test.ACTION"
    }
}
