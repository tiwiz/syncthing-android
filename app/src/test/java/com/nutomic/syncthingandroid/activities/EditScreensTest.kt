package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
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
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * Editing, creating and removing folders and devices with Syncthing stopped: the screens write
 * `config.xml` through ConfigRouter.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
        ],
)
class EditScreensTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        StoppedSyncthingService.install()
    }

    private fun savedConfig() = ConfigXml(context).apply { loadConfig() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private inline fun <reified T : Activity> launch(
        extras: Intent.() -> Unit
    ): ActivityScenario<T> =
        ActivityScenario.launchActivityForResult(Intent(context, T::class.java).apply(extras))

    private fun editFolder() =
        launch<FolderActivity> {
            putExtra(FolderActivity.EXTRA_IS_CREATE, false)
            putExtra(FolderActivity.EXTRA_FOLDER_ID, "cam-photos")
        }

    private fun editDevice() =
        DeviceScreen(
            launch<DeviceActivity> {
                putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                putExtra(DeviceActivity.EXTRA_DEVICE_ID, REMOTE_DEVICE_ID)
            },
            compose,
        )

    private fun newDevice() =
        DeviceScreen(
            launch<DeviceActivity> { putExtra(DeviceActivity.EXTRA_IS_CREATE, true) },
            compose,
        )

    /** Confirms the delete dialog the screen just opened. */
    private fun confirmDialog() {
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        idle()
    }

    @Test
    fun `renaming a folder is saved to config xml`() {
        editFolder().use { scenario ->
            scenario.onActivity {
                it.findViewById<EditText>(R.id.label).setText("Photos")
                it.onOptionsItemSelected(RoboMenuItem(R.id.save))
            }

            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals("Photos", savedConfig().folders.single().label)
        }
    }

    @Test
    fun `saving an unchanged folder writes nothing`() {
        val before = savedConfig().folders.single().label

        editFolder().use { scenario ->
            scenario.onActivity { it.onOptionsItemSelected(RoboMenuItem(R.id.save)) }

            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertEquals(before, savedConfig().folders.single().label)
        }
    }

    @Test
    fun `a folder without a label is not saved`() {
        editFolder().use { scenario ->
            scenario.onActivity {
                it.findViewById<EditText>(R.id.label).setText("")
                it.onOptionsItemSelected(RoboMenuItem(R.id.save))

                assertEquals(
                    "The folder label must not be empty",
                    ShadowToast.getTextOfLatestToast(),
                )
                assertFalse(it.isFinishing)
            }
            assertEquals("Camera", savedConfig().folders.single().label)
        }
    }

    @Test
    fun `removing a folder asks first, then deletes it from config xml`() {
        editFolder().use { scenario ->
            scenario.onActivity {
                it.onOptionsItemSelected(RoboMenuItem(R.id.remove))
                confirmDialog()
                assertTrue(it.isFinishing)
            }
            assertEquals(emptyList<String>(), savedConfig().folders.map { it.id })
        }
    }

    @Test
    fun `renaming a device is saved to config xml`() {
        editDevice().use { screen ->
            screen.setName("work laptop")
            screen.save()

            assertEquals(Activity.RESULT_OK, screen.resultCode())
            assertEquals(
                "work laptop",
                savedConfig().getDevices(false).single { it.deviceID == REMOTE_DEVICE_ID }.name,
            )
        }
    }

    @Test
    fun `removing a device asks first, then deletes it from config xml`() {
        editDevice().use { screen ->
            screen.delete()
            screen.confirmDialog()

            assertEquals(Activity.RESULT_OK, screen.resultCode())
            assertEquals(
                listOf(LOCAL_DEVICE_ID),
                savedConfig().getDevices(true).map { it.deviceID },
            )
        }
    }

    @Test
    fun `a new device needs a valid id`() {
        newDevice().use { screen ->
            screen.setNewDeviceId("not-a-device-id")
            screen.save()

            assertEquals(
                "The device ID is not formatted correctly. Please retry and enter the correct ID.",
                ShadowToast.getTextOfLatestToast(),
            )
            assertFalse(screen.finishing())
        }
    }

    @Test
    fun `a new device with a valid id is added to config xml`() {
        val newId = "AIR6LPZ-7K4PTTV-UXQSMUU-CPQ5YWH-OEDFIIQ-JUG777G-2YQXXR5-YD6AWQR"

        newDevice().use { screen ->
            screen.setNewDeviceId(newId)
            screen.setName("tablet")
            screen.save()

            assertEquals(Activity.RESULT_OK, screen.resultCode())
            assertEquals(
                "tablet",
                savedConfig().getDevices(false).single { it.deviceID == newId }.name,
            )
        }
    }
}
