package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
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

    @get:Rule val compose = createEmptyComposeRule()

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

    private fun select(type: String) {
        compose.onNodeWithText(type).performClick()
    }

    /** Taps Finish and returns the extras the activity set as its result, sorted by key. */
    private fun ActivityScenario<VersioningDialogActivity>.finish(): Map<String, String?> {
        compose.onNodeWithText("Finish").performScrollTo().performClick()
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
            select(SIMPLE)

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
            select(SIMPLE)
            select(NONE)

            assertEquals(mapOf("cleanoutDays" to "0", "keep" to "5", "type" to "none"), it.finish())
        }
    }

    @Test
    fun `edited numbers are returned, clamped to their range`() {
        launch("type" to "simple").use {
            compose.onNodeWithTag("keep").performTextReplacement("12")
            compose.onNodeWithTag("cleanoutDays").performTextReplacement("365")

            assertEquals(
                mapOf("cleanoutDays" to "100", "keep" to "12", "type" to "simple"),
                it.finish(),
            )
        }
    }

    @Test
    fun `the maximum age is shown in days and returned in seconds`() {
        launch("type" to "staggered", "maxAge" to "864000", "versionsPath" to "").use {
            compose.onNodeWithTag("maxAge").assertTextContains("10")
            compose.onNodeWithTag("maxAge").performTextReplacement("30")

            assertEquals("2592000", it.finish()["maxAge"])
        }
    }

    @Test
    fun `the external command is returned`() {
        launch("type" to "external").use {
            compose.onNodeWithTag("command").performTextReplacement("/bin/archive %FILE_PATH%")

            assertEquals("/bin/archive %FILE_PATH%", it.finish()["command"])
        }
    }

    @Test
    fun `the versions path comes from the folder picker`() {
        launch("type" to "staggered").use {
            compose.onNodeWithTag("versionsPath").performScrollTo().performClick()
            val app = ApplicationProvider.getApplicationContext<Application>()
            val started = checkNotNull(shadowOf(app).nextStartedActivityForResult)
            assertEquals(FolderPickerActivity::class.java.name, started.intent.component?.className)
            it.onActivity { activity ->
                shadowOf(activity)
                    .receiveResult(
                        started.intent,
                        Activity.RESULT_OK,
                        Intent().putExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY, "/sdcard/v"),
                    )
            }
            compose.onNodeWithText("/sdcard/v").assertExists()

            assertEquals("/sdcard/v", it.finish()["versionsPath"])
        }
    }

    @Test
    fun `the parameters survive recreating the screen`() {
        launch("type" to "trashcan").use {
            select(SIMPLE)
            compose.onNodeWithTag("keep").performTextReplacement("9")
            it.recreate()

            assertEquals(
                mapOf("cleanoutDays" to "0", "keep" to "9", "type" to "simple"),
                it.finish(),
            )
        }
    }

    private companion object {
        const val NONE = "None"
        const val SIMPLE = "Simple"
    }
}
