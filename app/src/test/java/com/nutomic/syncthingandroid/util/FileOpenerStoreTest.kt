package com.nutomic.syncthingandroid.util

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** [FileOpener.openFileManagerInStore] on a device with neither an app store nor a browser. */
@RunWith(AndroidJUnit4::class)
class FileOpenerStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `without a store or a browser it says so instead of crashing`() {
        // As on a device: starting an intent nothing handles throws.
        shadowOf(app).checkActivities(true)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        FileOpener.openFileManagerInStore(activity)

        assertEquals(
            app.getString(R.string.no_app_to_open_link),
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
