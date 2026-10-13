package com.nutomic.syncthingandroid

import android.app.Application
import android.os.StrictMode
import javax.inject.Inject

class SyncthingApp : Application() {

    @Inject lateinit var component: DaggerComponent

    override fun onCreate() {
        super.onCreate()
        DaggerDaggerComponent.builder().syncthingModule(SyncthingModule(this)).build().inject(this)
        // Set VM policy to avoid crash when sending folder URI to file manager.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }

    fun component(): DaggerComponent = component
}
