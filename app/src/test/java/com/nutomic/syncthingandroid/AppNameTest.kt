package com.nutomic.syncthingandroid

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.QuickSettingsTileCamera
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The fork is called "Expressive Syncthing" wherever it names itself, in every language: a brand
 * name, so not translated.
 */
@RunWith(AndroidJUnit4::class)
class AppNameTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun appLabel() = app.applicationInfo.loadLabel(app.packageManager).toString()

    private fun cameraTileLabel() =
        app.packageManager
            .getServiceInfo(ComponentName(app, QuickSettingsTileCamera::class.java), 0)
            .loadLabel(app.packageManager)
            .toString()

    @Test
    @Config(qualifiers = "en-rUS")
    fun `the app and its camera tile are named Expressive Syncthing`() {
        assertEquals("Expressive Syncthing", appLabel())
        assertEquals("Expressive Syncthing Camera", cameraTileLabel())
    }

    @Test
    @Config(qualifiers = "it-rIT")
    fun `the name is the same in a translated language`() {
        assertEquals("Expressive Syncthing", appLabel())
    }
}
