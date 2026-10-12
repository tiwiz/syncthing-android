package com.nutomic.syncthingandroid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** With no running Syncthing (`RestApi == null`) the router reads and writes `config.xml`. */
@RunWith(AndroidJUnit4::class)
class ConfigRouterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var router: ConfigRouter

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        router = ConfigRouter(context)
    }

    @Test
    fun `reads folders and devices from config xml`() {
        assertEquals(listOf("cam-photos"), router.getFolders(null).map { it.id })
        assertEquals(listOf(REMOTE_DEVICE_ID), router.getDevices(null, false).map { it.deviceID })
        assertEquals(
            setOf(LOCAL_DEVICE_ID, REMOTE_DEVICE_ID),
            router.getDevices(null, true).map { it.deviceID }.toSet(),
        )
        assertEquals(listOf("cam-photos"), router.getSharedFolders(REMOTE_DEVICE_ID).map { it.id })
        assertEquals("127.0.0.1:8384", router.getGui(null).address)
    }

    @Test
    fun `writes go to config xml`() {
        val folder = router.getFolders(null).single()
        folder.label = "Renamed"
        router.updateFolder(null, folder)
        assertEquals("Renamed", ConfigRouter(context).getFolders(null).single().label)

        router.removeDevice(null, REMOTE_DEVICE_ID)
        assertEquals(emptyList<String>(), ConfigRouter(context).getDevices(null, false).map { it.deviceID })

        router.removeFolder(null, "cam-photos")
        assertEquals(emptyList<String>(), ConfigRouter(context).getFolders(null).map { it.id })
    }

    @Test
    fun `ignoring a device or a folder needs a running syncthing`() {
        // CHARACTERIZATION: without the REST API both calls only log an error; config.xml is untouched.
        val before = Constants.getConfigFile(context).readText()
        router.ignoreDevice(null, "AAAAAAA-BBBBBBB", "stranger", "tcp://192.0.2.99:22000")
        router.ignoreFolder(null, REMOTE_DEVICE_ID, "secret-folder", "Secret")
        assertEquals(before, Constants.getConfigFile(context).readText())
    }
}
