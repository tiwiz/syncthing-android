package com.nutomic.syncthingandroid.testutil

import android.content.ContentResolver
import android.content.SyncStatusObserver
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

/**
 * Robolectric 4.17 does not shadow the sync status listeners on SDK 36, and the real method
 * dereferences a content service that does not exist in tests. `RunConditionMonitor` registers
 * one, so tests that create it use this shadow.
 */
@Implements(ContentResolver::class)
class ShadowContentResolverWithStatusListeners : ShadowContentResolver() {

    companion object {
        private val listeners = mutableMapOf<Any, SyncStatusObserver>()

        @JvmStatic
        @Implementation
        fun addStatusChangeListener(mask: Int, callback: SyncStatusObserver): Any {
            val handle = Any()
            listeners[handle] = callback
            return handle
        }

        @JvmStatic
        @Implementation
        fun removeStatusChangeListener(handle: Any?) {
            listeners.remove(handle)
        }

        val registeredListeners: Int
            get() = listeners.size
    }
}
