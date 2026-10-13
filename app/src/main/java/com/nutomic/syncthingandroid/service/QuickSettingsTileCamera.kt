package com.nutomic.syncthingandroid.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.nutomic.syncthingandroid.activities.PhotoShootActivity

/** Opens the photo screen; always shown as active. */
@RequiresApi(Build.VERSION_CODES.N)
class QuickSettingsTileCamera : TileService() {

    override fun onStartListening() {
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        startActivity(
            Intent(applicationContext, PhotoShootActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        super.onClick()
    }
}
