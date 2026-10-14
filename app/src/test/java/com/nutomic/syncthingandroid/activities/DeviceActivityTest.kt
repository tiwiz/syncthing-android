package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.drainMain
import com.nutomic.syncthingandroid.util.ConfigXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

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
            )
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
    fun `a new device can take the id of a device discovered nearby`() {
        val syncthing = FakeSyncthing(context)
        try {
            RunningSyncthingService.install(syncthing)
            DeviceScreen(
                    ActivityScenario.launchActivityForResult(DeviceActivity.createIntent(context))
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
internal class DeviceScreen(private val scenario: ActivityScenario<DeviceActivity>) :
    AutoCloseable {

    enum class Switch(val id: Int) {
        INTRODUCER(R.id.introducer),
        AUTO_ACCEPT(R.id.autoAcceptFolders),
        PAUSED(R.id.devicePause),
        UNTRUSTED(R.id.deviceUntrusted),
        CUSTOM_SYNC_CONDITIONS(R.id.customSyncConditionsSwitch),
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T> on(block: (DeviceActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it) }
        idle()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun setName(name: String) = on { it.findViewById<EditText>(R.id.name).setText(name) }

    fun setAddresses(addresses: String) = on {
        it.findViewById<EditText>(R.id.addresses).setText(addresses)
    }

    fun toggle(switch: Switch) = on { it.findViewById<SwitchCompat>(switch.id).toggle() }

    private fun DeviceActivity.folderRow(label: String): ViewGroup {
        val container = findViewById<ViewGroup>(R.id.foldersContainer)
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<ViewGroup>()
            .single { (it.getChildAt(0) as SwitchCompat).text.toString() == label }
    }

    fun folderShared(label: String): Boolean = on {
        (it.folderRow(label).getChildAt(0) as SwitchCompat).isChecked
    }

    fun toggleFolder(label: String) = on {
        (it.folderRow(label).getChildAt(0) as SwitchCompat).toggle()
    }

    fun setFolderPassword(label: String, password: String) = on {
        (it.folderRow(label).getChildAt(1) as EditText).setText(password)
    }

    fun chooseCompression(title: String) {
        on { it.findViewById<View>(R.id.compressionContainer).performClick() }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.listView
        val position =
            (0 until list.adapter.count).single { list.adapter.getItem(it).toString() == title }
        list.performItemClick(list.adapter.getView(position, null, list), position, 0)
        idle()
    }

    fun pickDiscovered(deviceId: String) = on {
        val container = it.findViewById<ViewGroup>(R.id.discoveredDevicesContainer)
        (0 until container.childCount)
            .map { i -> container.getChildAt(i) as TextView }
            .single { view -> view.text.startsWith(deviceId) }
            .performClick()
    }

    fun newDeviceId(): String = on { it.findViewById<EditText>(R.id.editDeviceId).text.toString() }

    fun save() = on { it.onOptionsItemSelected(RoboMenuItem(R.id.save)) }

    fun back() = on { it.onBackPressedDispatcher.onBackPressed() }

    fun confirmDialog() {
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        idle()
    }

    fun finishing(): Boolean = on { it.isFinishing }

    fun resultCode(): Int = scenario.result.resultCode

    override fun close() = scenario.close()
}
