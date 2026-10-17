package com.nutomic.syncthingandroid.recentchanges

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The recent changes list with Syncthing running, answered by [FakeSyncthing]. */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class RecentChangesActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var syncthing: FakeSyncthing

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        syncthing = FakeSyncthing(context)
        RunningSyncthingService.install(syncthing)
    }

    @After
    fun tearDown() {
        syncthing.close()
    }

    private fun diskEventRequests() = syncthing.requests("/rest/events/disk").size

    @Test
    fun `a recreation keeps the list without asking Syncthing again`() {
        ActivityScenario.launch(RecentChangesActivity::class.java).use { scenario ->
            awaitMain { diskEventRequests() == 1 }
            drainMain()
            compose.onNodeWithText("a.txt").assertExists()

            scenario.recreate()
            drainMain()

            compose.onNodeWithText("a.txt").assertExists()
            assertEquals(1, diskEventRequests())
        }
    }
}
