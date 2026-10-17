package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.nutomic.syncthingandroid.util.PermissionUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The permissions Syncthing Camera needs, and which of them the app has. */
internal data class CameraPermissions(val haveStorage: Boolean, val haveCamera: Boolean) {
    val all: Boolean
        get() = haveStorage && haveCamera
}

/**
 * Syncthing Camera's state. The picture in progress and whether the intro is shown survive a
 * recreation (the camera app often rotates the screen) and process death.
 */
@HiltViewModel
internal class PhotoShootViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val state = MutableStateFlow(readPermissions())
    val permissions: StateFlow<CameraPermissions> = state.asStateFlow()

    /** The file the camera app writes to, until the picture is taken. */
    var lastPhotoUri: Uri?
        get() = savedState[LAST_PHOTO_URI]
        set(value) {
            savedState[LAST_PHOTO_URI] = value
        }

    /** Whether the screen shows the intro, rather than going straight to the camera app. */
    var introShown: Boolean
        get() = savedState[INTRO_SHOWN] ?: false
        set(value) {
            savedState[INTRO_SHOWN] = value
        }

    fun updatePermissions() {
        state.value = readPermissions()
    }

    private fun readPermissions() =
        CameraPermissions(
            haveStorage = PermissionUtil.haveStoragePermission(),
            haveCamera =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )

    private companion object {
        const val LAST_PHOTO_URI = "lastPhotoUri"
        const val INTRO_SHOWN = "introShown"
    }
}
