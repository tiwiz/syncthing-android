package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.journeyapps.barcodescanner.BarcodeResult
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Adding a device by scanning its QR code: the scanner screen (camera permission, result, cancel)
 * and the device screen that opens it. Robolectric has no camera, so a scan is reported to the
 * scanner's callback directly.
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
class QrScanTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as Application

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        StoppedSyncthingService.install()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launchScanner(): ActivityScenario<QRScannerActivity> =
        ActivityScenario.launchActivityForResult(QRScannerActivity.intent(context))

    @Test
    fun `the scanner asks for the camera permission`() {
        launchScanner().use { scenario ->
            scenario.onActivity {
                val request = checkNotNull(shadowOf(it).lastRequestedPermission)
                assertEquals(
                    listOf(Manifest.permission.CAMERA),
                    request.requestedPermissions.toList(),
                )
            }
        }
    }

    @Test
    fun `without the camera permission the scanner closes`() {
        launchScanner().use { scenario ->
            scenario.onActivity {
                val request = checkNotNull(shadowOf(it).lastRequestedPermission)
                it.onRequestPermissionsResult(
                    request.requestCode,
                    request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_DENIED),
                )
            }

            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test
    fun `a scanned code is returned`() {
        launchScanner().use { scenario ->
            scenario.onActivity {
                it.barcodeResult(
                    BarcodeResult(
                        Result(REMOTE_DEVICE_ID, null, null, BarcodeFormat.QR_CODE),
                        null,
                    )
                )
            }

            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals(
                REMOTE_DEVICE_ID,
                scenario.result.resultData.getStringExtra(QRScannerActivity.QR_RESULT_ARG),
            )
        }
    }

    @Test
    fun `cancel closes the scanner`() {
        launchScanner().use { scenario ->
            compose.onNodeWithText("Cancel").performClick()

            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test
    fun `a scanned device id fills in the new device`() {
        ActivityScenario.launch<DeviceActivity>(DeviceActivity.createIntent(context)).use { scenario
            ->
            compose.onNodeWithContentDescription("Scan QR Code").performClick()
            idle()
            val started = checkNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(QRScannerActivity::class.java.name, started.component?.className)

            scenario.onActivity {
                shadowOf(it)
                    .receiveResult(
                        started,
                        Activity.RESULT_OK,
                        Intent().putExtra(QRScannerActivity.QR_RESULT_ARG, REMOTE_DEVICE_ID),
                    )
            }
            idle()

            assertEquals(
                REMOTE_DEVICE_ID,
                compose
                    .onNodeWithTag("deviceId")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText]
                    .text,
            )
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }
}
