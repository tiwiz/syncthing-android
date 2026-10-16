package com.nutomic.syncthingandroid

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.NotificationHandler
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class SyncthingModule(private val app: SyncthingApp) {

    @Provides
    @Singleton
    fun getPreferences(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)

    @Provides
    @Singleton
    fun getNotificationHandler(): NotificationHandler = NotificationHandler(app)
}
