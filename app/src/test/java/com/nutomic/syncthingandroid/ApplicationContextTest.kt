package com.nutomic.syncthingandroid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationContextTest {

    @Test
    fun `robolectric starts the real application with its dagger graph`() {
        val app = ApplicationProvider.getApplicationContext<SyncthingApp>()

        assertNotNull(app.component())
        assertEquals("com.github.catfriend1.syncthingfork.debug", app.packageName)
    }
}
