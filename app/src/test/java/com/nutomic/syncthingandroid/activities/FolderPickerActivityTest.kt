package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

/**
 * [FolderPickerActivity] limited to a temporary root folder, so the listing does not depend on the
 * machine: listing, entering and leaving folders, creating one and selecting the result.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class FolderPickerActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before
    fun setUp() {
        // The picker binds to the service, like every SyncthingActivity.
        StoppedSyncthingService.install()
        root =
            File(context.cacheDir, "picker-root").apply {
                deleteRecursively()
                File(this, "Music/Albums").mkdirs()
                File(this, "documents").mkdirs()
                File(this, "notes.txt").writeText("x")
                File(this, "A.txt").writeText("x")
            }
    }

    private fun launch(initial: String? = null) =
        ActivityScenario.launchActivityForResult<FolderPickerActivity>(
            FolderPickerActivity.createIntent(context, initial, root.path)
        )

    private fun FolderPickerActivity.listed(): List<String> {
        val list = findViewById<ListView>(android.R.id.list)
        return (0 until list.adapter.count).map { (list.adapter.getItem(it) as File).name }
    }

    private fun FolderPickerActivity.currentPath(): String =
        findViewById<TextView>(R.id.currentPath).text.toString()

    private fun FolderPickerActivity.open(name: String) {
        val list = findViewById<ListView>(android.R.id.list)
        val position = listed().indexOf(name)
        list.performItemClick(list.adapter.getView(position, null, list), position, 0)
    }

    @Test
    fun `a single root is opened directly, folders first, names ignoring case`() {
        launch().use { scenario ->
            scenario.onActivity {
                assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), it.listed())
                assertTrue(it.currentPath(), it.currentPath().endsWith(root.absolutePath))
            }
        }
    }

    @Test
    fun `clicking a folder enters it, files do nothing`() {
        launch().use { scenario ->
            scenario.onActivity {
                it.open("notes.txt")
                assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), it.listed())

                it.open("Music")
                assertEquals(listOf("Albums"), it.listed())
            }
        }
    }

    @Test
    fun `going up stops at the root`() {
        launch(initial = File(root, "Music/Albums").path).use { scenario ->
            scenario.onActivity {
                it.onOptionsItemSelected(RoboMenuItem(R.id.folder_go_up))
                assertEquals(listOf("Albums"), it.listed())
                it.onOptionsItemSelected(RoboMenuItem(R.id.folder_go_up))
                assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), it.listed())

                // A single root has no list of roots above it.
                assertEquals(false, it.canGoUpToSubDir())
                assertEquals(false, it.canGoUpToRootDir())
            }
        }
    }

    @Test
    fun `select returns the folder shown`() {
        launch(initial = File(root, "Music").path).use { scenario ->
            scenario.onActivity { it.onOptionsItemSelected(RoboMenuItem(R.id.select)) }

            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            // formatPath: a URI path, so a folder ends with a slash.
            assertEquals(
                File(root, "Music").absolutePath + "/",
                scenario.result.resultData.getStringExtra(
                    FolderPickerActivity.EXTRA_RESULT_DIRECTORY
                ),
            )
        }
    }

    @Test
    fun `a new folder is created and entered`() {
        launch().use { scenario ->
            scenario.onActivity {
                it.onOptionsItemSelected(RoboMenuItem(R.id.create_folder))
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                checkNotNull(dialog.window).decorView.editTexts().single().setText("Photos")
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                // AlertDialog delivers button clicks through a handler.
                shadowOf(Looper.getMainLooper()).idle()

                assertTrue(File(root, "Photos").isDirectory)
                assertTrue(it.currentPath(), it.currentPath().endsWith("Photos"))
                assertEquals(emptyList<String>(), it.listed())
            }
        }
    }

    private fun View.editTexts(): List<EditText> =
        when (this) {
            is EditText -> listOf(this)
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).editTexts() }
            else -> emptyList()
        }
}
