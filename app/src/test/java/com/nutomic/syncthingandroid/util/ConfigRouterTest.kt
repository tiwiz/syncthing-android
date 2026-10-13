package com.nutomic.syncthingandroid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.awaitMain
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element

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
        assertEquals(
            emptyList<String>(),
            ConfigRouter(context).getDevices(null, false).map { it.deviceID },
        )

        router.removeFolder(null, "cam-photos")
        assertEquals(emptyList<String>(), ConfigRouter(context).getFolders(null).map { it.id })
    }

    @Test
    fun `ignoring a device while stopped records it once in config xml`() {
        router.ignoreDevice(null, STRANGER_ID, "stranger", "tcp://192.0.2.99:22000")
        router.ignoreDevice(null, STRANGER_ID, "stranger", "tcp://192.0.2.99:22000")

        val ignored = savedElements("remoteIgnoredDevice")
        assertEquals(1, ignored.size)
        assertEquals(STRANGER_ID, ignored[0].getAttribute("id"))
        assertEquals("stranger", ignored[0].getAttribute("name"))
        assertEquals("tcp://192.0.2.99:22000", ignored[0].getAttribute("address"))
        assertTrue(ignored[0].getAttribute("time").isNotEmpty())
    }

    @Test
    fun `ignoring a folder while stopped records it once on the offering device`() {
        router.ignoreFolder(null, REMOTE_DEVICE_ID, "secret-folder", "Secret")
        router.ignoreFolder(null, REMOTE_DEVICE_ID, "secret-folder", "Secret")

        val remote = ConfigRouter(context).getDevices(null, false).single()
        assertEquals(listOf("secret-folder"), remote.ignoredFolders!!.map { it.id })
        assertEquals("Secret", remote.ignoredFolders!!.single().label)
        assertTrue(remote.ignoredFolders!!.single().time.isNotEmpty())
    }

    @Test
    fun `ignoring a folder of an unknown device while stopped records nothing`() {
        router.ignoreFolder(null, STRANGER_ID, "secret-folder", "Secret")

        assertTrue(savedElements("ignoredFolder").isEmpty())
    }

    private fun savedElements(tag: String): List<Element> {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Constants.getConfigFile(context))
        val nodes = document.documentElement.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        const val STRANGER_ID = "AIR6LPZ-7K4PTTV-UXQSMUU-CPQ5YWH-OEDFIIQ-JUG777G-2YQXXR5-YD6AWQR"
    }

    @Test
    @Config(
        shadows =
            [
                ShadowEnvironmentWithAllFilesAccess::class,
                ShadowContentResolverWithStatusListeners::class,
                ShadowUtilDeviceShell::class,
            ],
        instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
    )
    fun `while Syncthing runs, reads and writes go through the REST API`() {
        FakeSyncthing(context).use { syncthing ->
            val api = checkNotNull(RunningSyncthingService.install(syncthing).api)
            Constants.getConfigFile(context).delete()

            val folder = router.getFolders(api).single()
            assertEquals("cam-photos", folder.id)
            assertEquals("127.0.0.1:${syncthing.url.port}", router.getGui(api).address)

            folder.label = "Over REST"
            router.updateFolder(api, folder)
            awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

            val posted =
                syncthing.requests("/rest/system/config", "POST").last().body?.utf8().orEmpty()
            assertTrue(posted, posted.contains("\"label\":\"Over REST\""))
        }
    }
}
