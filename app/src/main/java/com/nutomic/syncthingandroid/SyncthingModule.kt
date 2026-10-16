package com.nutomic.syncthingandroid

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.NotificationHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App-wide dependencies. */
@Module
@InstallIn(SingletonComponent::class)
object SyncthingModule {

    @Provides
    @Singleton
    fun getPreferences(@ApplicationContext context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    @Provides
    @Singleton
    fun getNotificationHandler(@ApplicationContext context: Context): NotificationHandler =
        NotificationHandler(context)
}

/**
 * The app-wide dependencies for classes Hilt does not create (the REST client, the event processor,
 * the run condition monitor, the native process runner).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppDependencies {
    fun preferences(): SharedPreferences

    fun notificationHandler(): NotificationHandler
}

fun Context.appDependencies(): AppDependencies =
    EntryPointAccessors.fromApplication(applicationContext, AppDependencies::class.java)
