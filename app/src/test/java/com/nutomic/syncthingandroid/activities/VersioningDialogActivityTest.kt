package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What [VersioningDialogActivity] hands back to FolderActivity: the chosen type and its parameters,
 * with defaults filled in by the type's fragment.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class VersioningDialogActivityTest {

    private fun launch(
        vararg params: Pair<String, String>
    ): ActivityScenario<VersioningDialogActivity> {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                VersioningDialogActivity::class.java,
            )
        params.forEach { (key, value) -> intent.putExtra(key, value) }
        return ActivityScenario.launchActivityForResult(intent)
    }

    private fun ActivityScenario<VersioningDialogActivity>.select(type: Int) {
        onActivity { it.findViewById<Spinner>(R.id.versioningTypeSpinner).setSelection(type) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Taps Finish and returns the extras the activity set as its result, sorted by key. */
    private fun ActivityScenario<VersioningDialogActivity>.finish(): Map<String, String?> {
        onActivity { it.findViewById<Button>(R.id.finish_btn).performClick() }
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val extras: Bundle = checkNotNull(result.resultData.extras)
        return extras.keySet().sorted().associateWith { extras.getString(it) }
    }

    @Test
    fun `each type fills in its default parameters`() {
        val expected =
            mapOf(
                "none" to mapOf("type" to "none"),
                "trashcan" to mapOf("cleanoutDays" to "0", "type" to "trashcan"),
                "simple" to mapOf("cleanoutDays" to "0", "keep" to "5", "type" to "simple"),
                "staggered" to mapOf("maxAge" to "0", "type" to "staggered", "versionsPath" to ""),
                "external" to mapOf("command" to "", "type" to "external"),
            )
        expected.forEach { (type, params) ->
            launch("type" to type).use { assertEquals(type, params, it.finish()) }
        }
    }

    @Test
    fun `existing parameters are kept`() {
        launch(
                "type" to "staggered",
                "maxAge" to "2592000",
                "versionsPath" to "/storage/emulated/0/.versions",
            )
            .use {
                assertEquals(
                    mapOf(
                        "maxAge" to "2592000",
                        "type" to "staggered",
                        "versionsPath" to "/storage/emulated/0/.versions",
                    ),
                    it.finish(),
                )
            }
    }

    @Test
    fun `choosing another type returns it with its defaults and keeps the shared parameters`() {
        launch("type" to "trashcan", "cleanoutDays" to "7").use {
            it.select(SIMPLE)

            assertEquals(
                mapOf("cleanoutDays" to "7", "keep" to "5", "type" to "simple"),
                it.finish(),
            )
        }
    }

    @Test
    fun `back also saves the choice`() {
        launch("type" to "simple").use {
            it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }

            assertEquals(Activity.RESULT_OK, it.result.resultCode)
            assertEquals("simple", it.result.resultData.getStringExtra("type"))
        }
    }

    @Test
    fun `going back to the original type returns the original type`() {
        launch("type" to "none").use {
            it.select(SIMPLE)
            it.select(NONE)

            assertEquals(mapOf("cleanoutDays" to "0", "keep" to "5", "type" to "none"), it.finish())
        }
    }

    private companion object {
        const val NONE = 0
        const val SIMPLE = 2
    }
}
