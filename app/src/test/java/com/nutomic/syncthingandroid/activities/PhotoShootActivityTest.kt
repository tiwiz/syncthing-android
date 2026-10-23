package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * The Syncthing Camera screen: the intro asking for consent and permissions, and taking a picture
 * through the system camera app into the camera folder.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class PhotoShootActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as Application
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun setUp() {
        // FileProvider keeps the roots of the first test's directories in a static cache.
        val cache =
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        // A camera app that takes pictures.
        val camera = ComponentName("com.example.camera", "com.example.camera.Capture")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(camera)
            addIntentFilterForActivity(
                camera,
                IntentFilter(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
            )
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun grantCamera() = shadowOf(app).grantPermissions(Manifest.permission.CAMERA)

    private fun launch(): ActivityScenario<PhotoShootActivity> =
        ActivityScenario.launchActivityForResult(Intent(context, PhotoShootActivity::class.java))

    /** The picture request sent to the camera app, if any. */
    private fun captureRequest(): Intent? =
        shadowOf(app).nextStartedActivityForResult?.intent?.takeIf {
            it.action == MediaStore.ACTION_IMAGE_CAPTURE
        }

    private fun Intent.output(): Uri? =
        IntentCompat.getParcelableExtra(this, MediaStore.EXTRA_OUTPUT, Uri::class.java)

    @Test
    fun `without a camera the screen closes`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)

        launch().use {
            assertEquals(Activity.RESULT_CANCELED, it.result.resultCode)
            assertEquals(
                "Sorry, this device doesn't have a camera.",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun `after consent a picture is taken right away into the pictures folder`() {
        grantCamera()
        prefs.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true).commit()

        launch().use {
            val request = checkNotNull(captureRequest())
            val output = checkNotNull(request.output())
            assertEquals("${context.packageName}.provider", output.authority)
            assertTrue(output.path.orEmpty(), output.lastPathSegment.orEmpty().startsWith("IMG_"))
        }
    }

    @Test
    fun `continue without the camera permission asks for it`() {
        launch().use { scenario ->
            assertNull(captureRequest())
            clickContinue()

            assertEquals(
                "We require storage and camera permission to continue.",
                ShadowToast.getTextOfLatestToast(),
            )
            assertNull(captureRequest())
            assertFalse(prefs.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false))
        }
    }

    @Test
    fun `the camera permission button asks for the permission`() {
        launch().use { scenario ->
            clickGrantCamera()
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
    fun `continue with the permissions stores the consent and takes a picture`() {
        grantCamera()

        launch().use { scenario ->
            clickContinue()

            assertTrue(prefs.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false))
            assertNotNull(captureRequest())
        }
    }

    @Test
    fun `a taken picture is reported and closes the screen`() {
        grantCamera()
        prefs.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true).commit()

        launch().use { scenario ->
            val request = checkNotNull(captureRequest())
            // What the camera app does: write the picture to the given file.
            checkNotNull(context.contentResolver.openOutputStream(checkNotNull(request.output())))
                .use {
                    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
            scenario.onActivity { shadowOf(it).receiveResult(request, Activity.RESULT_OK, null) }
            idle()

            assertEquals(
                "Successfully saved picture to the Syncthing Camera folder.",
                ShadowToast.getTextOfLatestToast(),
            )
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Decodes for real, as on a device.
    fun `a picture too large to decode whole is still reported`() {
        grantCamera()
        prefs.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true).commit()

        launch().use { scenario ->
            val request = checkNotNull(captureRequest())
            checkNotNull(context.contentResolver.openOutputStream(checkNotNull(request.output())))
                .use { it.write(pngHeader(width = 30_000, height = 30_000)) }
            scenario.onActivity { shadowOf(it).receiveResult(request, Activity.RESULT_OK, null) }
            idle()

            assertEquals(
                "Successfully saved picture to the Syncthing Camera folder.",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    /** A PNG of [width] x [height] pixels without pixel data: enough to read its size only. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val ihdr =
            ByteBuffer.allocate(IHDR_LENGTH)
                .putInt(width)
                .putInt(height)
                .put(byteArrayOf(8, 6, 0, 0, 0)) // 8 bits per channel, RGBA, no interlacing.
                .array()
        return PNG_SIGNATURE +
            chunk("IHDR", ihdr) +
            chunk("IDAT", ByteArray(0)) +
            chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeAndData = type.toByteArray() + data
        val crc = CRC32().apply { update(typeAndData) }.value.toInt()
        return ByteBuffer.allocate(Int.SIZE_BYTES * 2 + typeAndData.size)
            .putInt(data.size)
            .put(typeAndData)
            .putInt(crc)
            .array()
    }

    @Test
    fun `a recreation while the camera is open asks for no second picture`() {
        grantCamera()
        prefs.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true).commit()

        launch().use { scenario ->
            checkNotNull(captureRequest())

            scenario.recreate()
            idle()

            assertNull(captureRequest())
        }
    }

    @Test
    fun `a picture taken after a recreation is still reported`() {
        grantCamera()
        prefs.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true).commit()

        launch().use { scenario ->
            val request = checkNotNull(captureRequest())
            checkNotNull(context.contentResolver.openOutputStream(checkNotNull(request.output())))
                .use {
                    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.JPEG, 90, it)
                }

            scenario.recreate()
            scenario.onActivity { shadowOf(it).receiveResult(request, Activity.RESULT_OK, null) }
            idle()

            assertEquals(
                "Successfully saved picture to the Syncthing Camera folder.",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    private fun clickContinue() {
        compose.onNodeWithText("Continue").performClick()
        idle()
    }

    private fun clickGrantCamera() {
        compose.onNodeWithText("Grant camera permission").performClick()
        idle()
    }

    private companion object {
        const val IHDR_LENGTH = 13
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
