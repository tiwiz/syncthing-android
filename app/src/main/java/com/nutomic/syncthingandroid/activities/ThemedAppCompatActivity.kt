package com.nutomic.syncthingandroid.activities

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.Constants

/** An activity in the app theme the user picked, drawn edge to edge like every Compose screen. */
open class ThemedAppCompatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val appTheme =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString(
                    Constants.PREF_APP_THEME,
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString(),
                )
        AppCompatDelegate.setDefaultNightMode(checkNotNull(appTheme).toInt())
        super.onCreate(savedInstanceState)
        // After super.onCreate, so the system bars follow the night mode just applied.
        enableEdgeToEdge()
    }
}
