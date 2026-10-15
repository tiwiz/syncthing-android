package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder

/** Connects to [SyncthingService] while shown and gives access to it. */
open class SyncthingActivity : ThemedAppCompatActivity(), ServiceConnection {

    /** The service, or null while not bound. */
    var service: SyncthingService? = null
        private set

    /** The REST API, or null while the service is not bound or Syncthing does not run. */
    val api: RestApi?
        get() = service?.api

    override fun onPause() {
        unbindService(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        bindService(Intent(this, SyncthingService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        service = (iBinder as SyncthingServiceBinder).service
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        service = null
    }
}
