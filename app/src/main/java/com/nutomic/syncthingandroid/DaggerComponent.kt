package com.nutomic.syncthingandroid

import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.PhotoShootActivity
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.activities.SyncConditionsActivity
import com.nutomic.syncthingandroid.fragments.DeviceListFragment
import com.nutomic.syncthingandroid.fragments.FolderListFragment
import com.nutomic.syncthingandroid.fragments.StatusFragment
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.service.EventProcessor
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingRunnable
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.settings.SettingsActivity
import dagger.Component
import javax.inject.Singleton

// One inject method per class that receives dependencies.
@Suppress("TooManyFunctions")
@Singleton
@Component(modules = [SyncthingModule::class])
interface DaggerComponent {
    fun inject(activity: DeviceActivity)

    fun inject(fragment: DeviceListFragment)

    fun inject(eventProcessor: EventProcessor)

    fun inject(activity: FolderActivity)

    fun inject(fragment: FolderListFragment)

    fun inject(activity: MainActivity)

    fun inject(onboardingActivity: OnboardingActivity)

    fun inject(photoShootActivity: PhotoShootActivity)

    fun inject(restApi: RestApi)

    fun inject(runConditionMonitor: RunConditionMonitor)

    fun inject(settingsActivity: SettingsActivity)

    fun inject(activity: ShareActivity)

    fun inject(fragment: StatusFragment)

    fun inject(activity: SyncConditionsActivity)

    fun inject(app: SyncthingApp)

    fun inject(syncthingRunnable: SyncthingRunnable)

    fun inject(service: SyncthingService)
}
