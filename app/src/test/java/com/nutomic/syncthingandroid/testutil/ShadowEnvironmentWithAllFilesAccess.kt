package com.nutomic.syncthingandroid.testutil

import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/**
 * Robolectric 4.17 has no external storage volumes for the current user on SDK 36, so the real
 * `Environment.isExternalStorageManager()` crashes. Tests that start `SyncthingService` run as if
 * the user granted "All files access" during onboarding.
 */
@Implements(Environment::class)
class ShadowEnvironmentWithAllFilesAccess : ShadowEnvironment() {
    companion object {
        @JvmStatic @Implementation fun isExternalStorageManager(): Boolean = true
    }
}
