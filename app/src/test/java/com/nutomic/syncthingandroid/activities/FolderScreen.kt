package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf

/**
 * Drives the folder screen the way a user would, so the tests stay the same whatever the screen is
 * made of.
 */
internal class FolderScreen(
    private val scenario: ActivityScenario<FolderActivity>,
    private val compose: ComposeTestRule,
) : AutoCloseable {

    enum class Switch(val title: String) {
        FILE_WATCHER("Watch for changes"),
        PAUSED("Pause Folder"),
        CUSTOM_SYNC_CONDITIONS("Custom sync conditions"),
        IGNORE_DELETE("Ignore Delete"),
        RUN_SCRIPT("Run Script"),
    }

    /** The parts of the screen that open another screen. */
    enum class Opener(val tag: String?, val text: String? = null) {
        FOLDER_TYPE("folderType"),
        PULL_ORDER("pullOrder"),
        VERSIONING("versioning"),
        SYNC_CONDITIONS("syncConditions"),
        ADVANCED_DIRECTORY(null, "Advanced Directory Selection"),
    }

    /** The texts the screen shows for a setting: a tagged row and which of its texts. */
    enum class Shown(val tag: String, val index: Int) {
        FOLDER_TYPE("folderType", 1),
        PULL_ORDER("pullOrder", 1),
        VERSIONING("versioning", 1),
        VERSIONING_DESCRIPTION("versioning", 2),
        PATH("path", 0),
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun tagged(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    fun setLabel(label: String) {
        tagged("label").performTextReplacement(label)
    }

    fun setId(id: String) {
        tagged("id").performTextReplacement(id)
    }

    fun setIgnoreList(text: String) {
        tagged("ignoreList").performTextReplacement(text)
    }

    fun ignoreList(): String =
        tagged("ignoreList").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    fun toggle(switch: Switch) {
        compose.onNodeWithText(switch.title).performScrollTo().performClick()
    }

    fun shown(shown: Shown): String =
        tagged(shown.tag).fetchSemanticsNode().config[SemanticsProperties.Text][shown.index].text

    fun isEnabled(opener: Opener): Boolean =
        SemanticsProperties.Disabled !in node(opener).fetchSemanticsNode().config

    /** Whether the screen shows [opener] at all. */
    fun offers(opener: Opener): Boolean =
        compose.onAllNodesWithTag(checkNotNull(opener.tag)).fetchSemanticsNodes().isNotEmpty()

    private fun node(opener: Opener) =
        if (opener.tag != null) tagged(opener.tag)
        else compose.onNodeWithText(checkNotNull(opener.text)).performScrollTo()

    /** Opens what [opener] opens and returns the screen it started, if any. */
    fun open(opener: Opener): Intent? {
        node(opener).performClick()
        idle()
        return shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .nextStartedActivity
    }

    /** Hands [data] back as the result of the screen [started]. */
    fun receive(started: Intent, data: Intent) {
        scenario.onActivity { shadowOf(it).receiveResult(started, Activity.RESULT_OK, data) }
        idle()
    }

    fun deviceShared(name: String): Boolean =
        compose
            .onNodeWithText(name)
            .performScrollTo()
            .fetchSemanticsNode()
            .config[SemanticsProperties.Selected]

    fun toggleDevice(name: String) {
        compose.onNodeWithText(name).performScrollTo().performClick()
    }

    fun setDevicePassword(name: String, password: String) {
        tagged("password:$name").performTextReplacement(password)
    }

    fun save() {
        compose
            .onNode(hasContentDescription("Save") or hasContentDescription("Create"))
            .performClick()
        idle()
    }

    fun delete() {
        compose.onNodeWithContentDescription("Delete Folder").performClick()
    }

    fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        idle()
    }

    fun confirmDialog() {
        compose.onNode((hasText("OK") or hasText("Yes")) and hasClickAction()).performClick()
        idle()
    }

    fun finishing(): Boolean {
        var finishing = false
        scenario.onActivity { finishing = it.isFinishing }
        return finishing
    }

    fun resultCode(): Int = scenario.result.resultCode

    override fun close() = scenario.close()
}
