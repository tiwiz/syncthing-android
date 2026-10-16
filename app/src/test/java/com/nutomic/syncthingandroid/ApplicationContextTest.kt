package com.nutomic.syncthingandroid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationContextTest {

    @Test
    fun `robolectric starts the real application with its hilt graph`() {
        val app = ApplicationProvider.getApplicationContext<SyncthingApp>()

        val dependencies = app.appDependencies()
        assertNotNull(dependencies.preferences())
        // Singletons: every class gets the same instances.
        assertSame(dependencies.notificationHandler(), app.appDependencies().notificationHandler())
        assertEquals("com.github.catfriend1.syncthingfork.debug", app.packageName)
    }
}
