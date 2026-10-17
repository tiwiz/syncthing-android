package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import com.nutomic.syncthingandroid.util.ConfigXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The device screen beyond renaming and removing ([EditScreensTest]): addresses, folder shares with
 * their encryption passwords, unsaved changes, its own sync conditions, compression and the devices
 * Syncthing discovered nearby. The screen is driven through [DeviceScreen], so the tests stay the
 * same whatever the screen is made of.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class DeviceActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
    }

    private fun savedConfig() = ConfigXml(context).apply { loadConfig() }

    private fun savedLaptop() =
        savedConfig().getDevices(false).single { it.deviceID == REMOTE_DEVICE_ID }

    private fun editLaptop(): DeviceScreen {
        StoppedSyncthingService.install()
        return DeviceScreen(
            ActivityScenario.launchActivityForResult(
                Intent(context, DeviceActivity::class.java)
                    .putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                    .putExtra(DeviceActivity.EXTRA_DEVICE_ID, REMOTE_DEVICE_ID)
            ),
            compose,
        )
    }

    @Test
    fun `addresses are split on commas, semicolons and spaces`() {
        editLaptop().use { screen ->
            screen.setAddresses("tcp://192.0.2.1:22000;dynamic  tcp://192.0.2.2:22000")
            screen.save()

            assertEquals(
                listOf("tcp://192.0.2.1:22000", "dynamic", "tcp://192.0.2.2:22000"),
                savedLaptop().addresses,
            )
        }
    }

    @Test
    fun `no addresses means dynamic`() {
        editLaptop().use { screen ->
            screen.setAddresses("")
            screen.save()

            assertEquals(listOf("dynamic"), savedLaptop().addresses)
        }
    }

    @Test
    fun `unsharing a folder removes the device from it`() {
        editLaptop().use { screen ->
            assertTrue(screen.folderShared("Camera"))
            screen.toggleFolder("Camera")
            screen.save()

            val folder = savedConfig().folders.single()
            assertFalse(folder.sharedWithDevices.orEmpty().any { it.deviceID == REMOTE_DEVICE_ID })
        }
    }

    @Test
    fun `a shared folder keeps its encryption password`() {
        editLaptop().use { screen ->
            screen.setFolderPassword("Camera", "s3cret")
            screen.save()

            val share =
                savedConfig().folders.single().sharedWithDevices.orEmpty().single {
                    it.deviceID == REMOTE_DEVICE_ID
                }
            assertEquals("s3cret", share.encryptionPassword)
        }
    }

    @Test
    fun `switches are saved`() {
        editLaptop().use { screen ->
            screen.toggle(DeviceScreen.Switch.INTRODUCER)
            screen.toggle(DeviceScreen.Switch.AUTO_ACCEPT)
            screen.toggle(DeviceScreen.Switch.PAUSED)
            screen.toggle(DeviceScreen.Switch.UNTRUSTED)
            screen.save()

            val laptop = savedLaptop()
            assertTrue(laptop.introducer)
            assertTrue(laptop.autoAcceptFolders)
            assertTrue(laptop.paused)
            assertTrue(laptop.untrusted)
        }
    }

    @Test
    fun `own sync conditions are switched on with the device`() {
        val pref =
            Constants.dynPrefObjectCustomSyncConditions(
                Constants.PREF_OBJECT_PREFIX_DEVICE + REMOTE_DEVICE_ID
            )
        editLaptop().use { screen ->
            screen.toggle(DeviceScreen.Switch.CUSTOM_SYNC_CONDITIONS)
            screen.save()
        }

        assertTrue(prefs.getBoolean(pref, false))
    }

    @Test
    fun `leaving with unsaved changes asks first`() {
        editLaptop().use { screen ->
            screen.setName("renamed")
            screen.back()
            assertFalse(screen.finishing())

            screen.confirmDialog()

            assertTrue(screen.finishing())
            assertEquals(Activity.RESULT_CANCELED, screen.resultCode())
            assertEquals("laptop", savedLaptop().name)
        }
    }

    @Test
    fun `unsaved changes survive recreation`() {
        editLaptop().use { screen ->
            screen.setName("desk")

            screen.recreate()

            assertEquals("desk", screen.name())
        }
    }

    @Test
    fun `leaving without changes does not ask`() {
        editLaptop().use { screen ->
            screen.back()

            assertTrue(screen.finishing())
        }
    }

    @Test
    fun `saving without changes leaves the device as it was`() {
        editLaptop().use { screen ->
            screen.save()

            assertEquals(Activity.RESULT_CANCELED, screen.resultCode())
            assertEquals("laptop", savedLaptop().name)
        }
    }

    @Test
    fun `compression is offered in expert mode and saved`() {
        prefs.edit().putBoolean(Constants.PREF_EXPERT_MODE, true).commit()

        editLaptop().use { screen ->
            screen.chooseCompression("Always")
            screen.save()

            assertEquals("always", savedLaptop().compression)
        }
    }

    @Test
    fun `the device's state shows while Syncthing runs`() {
        val syncthing = FakeSyncthing(context)
        try {
            RunningSyncthingService.install(syncthing)
            DeviceScreen(
                    ActivityScenario.launchActivityForResult(
                        Intent(context, DeviceActivity::class.java)
                            .putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                            .putExtra(DeviceActivity.EXTRA_DEVICE_ID, REMOTE_DEVICE_ID)
                    ),
                    compose,
                )
                .use { screen ->
                    // The fixture's completion leaves a few bytes for the laptop.
                    awaitMain { screen.state() == "Disconnected (3 B pending)" }
                    assertEquals("Disconnected (3 B pending)", screen.state())
                }
        } finally {
            syncthing.close()
        }
    }

    @Test
    fun `a new device can take the id of a device discovered nearby`() {
        val syncthing = FakeSyncthing(context)
        try {
            RunningSyncthingService.install(syncthing)
            DeviceScreen(
                    ActivityScenario.launchActivityForResult(DeviceActivity.createIntent(context)),
                    compose,
                )
                .use { screen ->
                    drainMain()
                    screen.pickDiscovered(DISCOVERED_ID)

                    assertEquals(DISCOVERED_ID, screen.newDeviceId())
                }
        } finally {
            syncthing.close()
        }
    }

    private companion object {
        const val DISCOVERED_ID = "B53EEZU-EX76N5D-KIZHSZC-XFUDUBO-LQM5GXS-DB3Z5NH-N5ESLFH-AJ62XAE"
    }
}

/** Drives the device screen the way a user would. */
internal class DeviceScreen(
    private val scenario: ActivityScenario<DeviceActivity>,
    private val compose: ComposeTestRule,
) : AutoCloseable {

    enum class Switch(val title: String) {
        INTRODUCER("Introducer"),
        AUTO_ACCEPT("Auto Accept"),
        PAUSED("Pause Device"),
        UNTRUSTED("Untrusted Device"),
        CUSTOM_SYNC_CONDITIONS("Custom sync conditions"),
    }

    private fun node(text: String) = compose.onNodeWithText(text).performScrollTo()

    fun setName(name: String) {
        compose.onNodeWithTag("name").performScrollTo().performTextReplacement(name)
    }

    fun name(): String =
        compose
            .onNodeWithTag("name")
            .performScrollTo()
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    /** Recreates the screen, as a rotation or a fold does. */
    fun recreate() {
        scenario.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    fun setAddresses(addresses: String) {
        compose.onNodeWithTag("addresses").performScrollTo().performTextReplacement(addresses)
    }

    /** The device's state as the screen shows it. */
    fun state(): String =
        compose
            .onNodeWithTag("editState", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()

    fun toggle(switch: Switch) {
        node(switch.title).performClick()
    }

    fun folderShared(label: String): Boolean =
        node(label).fetchSemanticsNode().config[SemanticsProperties.Selected]

    fun toggleFolder(label: String) {
        node(label).performClick()
    }

    fun setFolderPassword(label: String, password: String) {
        compose.onNodeWithTag("password:$label").performScrollTo().performTextReplacement(password)
    }

    fun chooseCompression(title: String) {
        node("Compression").performClick()
        compose.onNodeWithText(title).performClick()
    }

    fun pickDiscovered(deviceId: String) {
        compose.onNodeWithText(deviceId, substring = true).performScrollTo().performClick()
    }

    fun setNewDeviceId(deviceId: String) {
        compose.onNodeWithTag("deviceId").performTextReplacement(deviceId)
    }

    fun newDeviceId(): String =
        compose
            .onNodeWithTag("deviceId")
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    fun save() {
        compose
            .onNode(hasContentDescription("Save") or hasContentDescription("Create"))
            .performClick()
    }

    fun delete() {
        node("Delete Device").performClick()
    }

    fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    fun confirmDialog() {
        compose.onNode((hasText("OK") or hasText("Yes")) and hasClickAction()).performClick()
    }

    fun finishing(): Boolean {
        var finishing = false
        scenario.onActivity { finishing = it.isFinishing }
        return finishing
    }

    fun resultCode(): Int = scenario.result.resultCode

    override fun close() = scenario.close()
}
