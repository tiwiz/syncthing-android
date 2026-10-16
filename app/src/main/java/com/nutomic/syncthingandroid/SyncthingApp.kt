package com.nutomic.syncthingandroid

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SyncthingApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Set VM policy to avoid crash when sending folder URI to file manager.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}
