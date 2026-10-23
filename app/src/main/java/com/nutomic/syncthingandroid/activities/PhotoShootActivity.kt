package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.onboarding.OnboardingIcon
import com.nutomic.syncthingandroid.onboarding.OnboardingScaffold
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.PermissionUtil
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Syncthing Camera: takes a picture with the system camera app into the camera folder. The first
 * time, an intro asks for consent and the storage and camera permissions.
 */
@AndroidEntryPoint
class PhotoShootActivity : ThemedAppCompatActivity() {

    @Inject lateinit var preferences: SharedPreferences

    private val viewModel: PhotoShootViewModel by viewModels()

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onPictureTaken()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, R.string.photo_shoot_intro_no_camera, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.updatePermissions()
        if (savedInstanceState == null) {
            val consented = preferences.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
            val havePermissions = viewModel.permissions.value.all
            Log.v(
                TAG,
                "prefEnableSyncthingCamera=$consented, haveRequiredPermissions=$havePermissions",
            )
            if (havePermissions && consented) {
                Log.v(TAG, "User completed intro and consented before. Warp to take a picture.")
                openCameraIntent()
                return
            }
            viewModel.introShown = true
        } else if (!viewModel.introShown) {
            // The camera app is open; its result comes back here.
            return
        }
        setContent {
            val permissions by viewModel.permissions.collectAsStateWithLifecycle()
            ApplicationTheme {
                PhotoShootIntro(
                    haveStorage = permissions.haveStorage,
                    haveCamera = permissions.haveCamera,
                    onGrantStorage = { PermissionUtil.requestStoragePermission(this) },
                    onGrantCamera = {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.CAMERA),
                            REQUEST_CAMERA,
                        )
                    },
                    onBack = ::finish,
                    onContinue = ::onContinue,
                )
            }
        }
    }

    private fun onContinue() {
        viewModel.updatePermissions()
        if (!viewModel.permissions.value.all) {
            Toast.makeText(this, R.string.photo_shoot_intro_missing_permissions, Toast.LENGTH_LONG)
                .show()
            return
        }
        preferences.edit { putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true) }
        Log.v(TAG, "User completed intro and consented.")
        openCameraIntent()
    }

    private fun openCameraIntent() {
        val pictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (pictureIntent.resolveActivity(packageManager) == null) {
            Log.e(TAG, "This system does not support the ACTION_IMAGE_CAPTURE intent.")
            Toast.makeText(
                    this,
                    "This system does not support the ACTION_IMAGE_CAPTURE intent.",
                    Toast.LENGTH_LONG,
                )
                .show()
            finish()
            return
        }
        val photoFile =
            try {
                createImageFile()
            } catch (_: IOException) {
                Log.e(TAG, "Error occurred while creating the temp image file")
                return
            }
        if (photoFile == null) {
            Log.e(TAG, "openCameraIntent: photoFile == null")
            return
        }
        val photoUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        pictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        Log.d(TAG, "Launching take picture intent ...")
        viewModel.lastPhotoUri = photoUri
        takePicture.launch(pictureIntent)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir =
            FileUtils.getExternalFilesDir(
                this,
                FileUtils.ExternalStorageDirType.INT_MEDIA,
                Environment.DIRECTORY_PICTURES,
            )
        if (storageDir == null) {
            Log.e(TAG, "createImageFile: storageDir == null")
            return null
        }
        storageDir.mkdirs()
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    /** A picture the camera app could not write leaves an empty file, which is removed. */
    private fun onPictureTaken() {
        val photoUri = viewModel.lastPhotoUri
        viewModel.lastPhotoUri = null
        val taken =
            photoUri != null &&
                try {
                    hasPicture(photoUri)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Whatever the decoder throws means there is no picture.
                    Log.d(TAG, "User cancelled or failed to take a picture.", e)
                    false
                }
        if (!taken) {
            if (photoUri != null) {
                Log.v(TAG, "Deleting temporary file [${photoUri.path}]")
                try {
                    contentResolver.delete(photoUri, null, null)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // The provider may refuse; the empty file then stays.
                    Log.e(TAG, "Delete temporary file FAILED", e)
                }
            }
            Toast.makeText(this, R.string.photo_shoot_take_picture_failure, Toast.LENGTH_SHORT)
                .show()
            return
        }
        Log.d(TAG, "User took a picture.")
        Toast.makeText(this, R.string.photo_shoot_take_picture_success, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Whether [uri] holds an image; reads only its size, not its pixels. */
    private fun hasPicture(uri: Uri): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth > 0 && options.outHeight > 0
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQUEST_CAMERA) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "User denied CAMERA permission.")
        } else {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted CAMERA permission.")
            viewModel.updatePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        // "All files access" is granted in the system settings, not through a result.
        viewModel.updatePermissions()
    }

    private companion object {
        const val TAG = "PhotoShootActivity"
        const val REQUEST_CAMERA = 140
    }
}

/** Asks for consent and the permissions Syncthing Camera needs; granted ones are not offered. */
@Composable
private fun PhotoShootIntro(
    haveStorage: Boolean,
    haveCamera: Boolean,
    onGrantStorage: () -> Unit,
    onGrantCamera: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        icon = OnboardingIcon.Vector(Icons.Outlined.PhotoCamera),
        title = stringResource(R.string.photo_shoot_intro_welcome_title),
        description = stringResource(R.string.photo_shoot_intro_permission_desc),
        pageIndex = 0,
        pageCount = 1,
        onBack = onBack,
        onNext = onContinue,
        nextLabel = stringResource(R.string.cont),
        modifier = modifier,
        action =
            if (haveStorage && haveCamera) null
            else {
                {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (!haveStorage) {
                            GrantButton(
                                Icons.Outlined.Storage,
                                R.string.grant_storage_permission,
                                onGrantStorage,
                            )
                        }
                        if (!haveCamera) {
                            GrantButton(
                                Icons.Outlined.CameraAlt,
                                R.string.grant_camera_permission,
                                onGrantCamera,
                            )
                        }
                    }
                }
            },
    )
}

@Composable
private fun GrantButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(label))
    }
}
