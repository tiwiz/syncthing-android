package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.nutomic.syncthingandroid.R
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

/**
 * Drives the folder screen the way a user would, so the tests stay the same whatever the screen is
 * made of.
 */
internal class FolderScreen(
    private val scenario: ActivityScenario<FolderActivity>,
    @Suppress("unused") private val compose: ComposeTestRule,
) : AutoCloseable {

    enum class Switch(val id: Int) {
        FILE_WATCHER(R.id.fileWatcher),
        PAUSED(R.id.folderPause),
        CUSTOM_SYNC_CONDITIONS(R.id.customSyncConditionsSwitch),
        IGNORE_DELETE(R.id.ignoreDelete),
        RUN_SCRIPT(R.id.runScriptSwitch),
    }

    /** The parts of the screen that open another screen. */
    enum class Opener(val id: Int) {
        FOLDER_TYPE(R.id.folderTypeContainer),
        PULL_ORDER(R.id.pullOrderContainer),
        VERSIONING(R.id.versioningContainer),
        SYNC_CONDITIONS(R.id.customSyncConditionsDialog),
        ADVANCED_DIRECTORY(R.id.selectAdvancedDirectory),
    }

    /** The texts the screen shows for a setting. */
    enum class Shown(val id: Int) {
        FOLDER_TYPE(R.id.folderType),
        PULL_ORDER(R.id.pullOrderType),
        VERSIONING(R.id.versioningType),
        VERSIONING_DESCRIPTION(R.id.versioningDescription),
        PATH(R.id.directoryTextView),
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T> on(block: (FolderActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it) }
        idle()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun setLabel(label: String) = on { it.findViewById<EditText>(R.id.label).setText(label) }

    fun setId(id: String) = on { it.findViewById<EditText>(R.id.id).setText(id) }

    fun setIgnoreList(text: String) = on {
        it.findViewById<EditText>(R.id.edit_ignore_list_content).setText(text)
    }

    fun ignoreList(): String = on {
        it.findViewById<EditText>(R.id.edit_ignore_list_content).text.toString()
    }

    fun toggle(switch: Switch) = on { it.findViewById<SwitchCompat>(switch.id).toggle() }

    fun shown(shown: Shown): String = on { it.findViewById<TextView>(shown.id).text.toString() }

    fun isEnabled(opener: Opener): Boolean = on { it.findViewById<View>(opener.id).isEnabled }

    /** Opens what [opener] opens and returns the screen it started, if any. */
    fun open(opener: Opener): Intent? {
        on { it.findViewById<View>(opener.id).performClick() }
        return shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .nextStartedActivity
    }

    /** Hands [data] back as the result of the screen [started]. */
    fun receive(started: Intent, data: Intent) = on {
        shadowOf(it).receiveResult(started, Activity.RESULT_OK, data)
    }

    private fun FolderActivity.deviceRow(name: String): ViewGroup {
        val container = findViewById<ViewGroup>(R.id.devicesContainer)
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<ViewGroup>()
            .single { (it.getChildAt(0) as SwitchCompat).text.toString() == name }
    }

    fun deviceShared(name: String): Boolean = on {
        (it.deviceRow(name).getChildAt(0) as SwitchCompat).isChecked
    }

    fun toggleDevice(name: String) = on {
        (it.deviceRow(name).getChildAt(0) as SwitchCompat).toggle()
    }

    fun setDevicePassword(name: String, password: String) = on {
        (it.deviceRow(name).getChildAt(1) as EditText).setText(password)
    }

    fun save() = on { it.onOptionsItemSelected(RoboMenuItem(R.id.save)) }

    fun delete() = on { it.onOptionsItemSelected(RoboMenuItem(R.id.remove)) }

    fun back() = on { it.onBackPressedDispatcher.onBackPressed() }

    fun confirmDialog() {
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE)
            .performClick()
        idle()
    }

    fun finishing(): Boolean = on { it.isFinishing }

    fun resultCode(): Int = scenario.result.resultCode

    override fun close() = scenario.close()
}
