package com.nutomic.syncthingandroid.testutil

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.GsonBuilder
import com.nutomic.syncthingandroid.service.Constants
import java.io.File

/** Installs the fixture `config.xml` where the app expects it, with the matching preferences. */
object ConfigFixture {

    const val LOCAL_DEVICE_ID = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"
    const val REMOTE_DEVICE_ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"

    val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun install(context: Context, transform: (String) -> String = { it }): File {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(Constants.PREF_LOCAL_DEVICE_ID, LOCAL_DEVICE_ID)
            .commit()
        val file = Constants.getConfigFile(context)
        file.parentFile?.mkdirs()
        file.writeText(transform(Fixtures.text("syncthing/config.xml")))
        return file
    }
}
