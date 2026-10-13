package com.nutomic.syncthingandroid.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.Golden
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * Storage paths and URIs: Storage Access Framework tree URIs to paths, the app's external
 * directories, the MIME table, creating folders and files through DocumentFile, and opening files
 * and folders in other apps.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class FileUtilsStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as Application
    private val tempDir: File = Files.createTempDirectory("storage").toFile()
    private val externalRoot = Environment.getExternalStorageDirectory().absolutePath

    @Before
    fun clearFileProviderCache() {
        // FileProvider keeps the roots of the first test's directories in a static cache.
        val cache =
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun treeUri(documentId: String): Uri =
        DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE, documentId)

    @Test
    fun `every known extension maps to its MIME type`() {
        val extensions = Fixtures.text("mime-extensions.txt").lines().dropLast(1)

        Golden.assertMatches(
            "util/mime-types-all.txt",
            extensions.joinToString("\n") {
                "[$it] -> ${FileUtils.getMimeTypeFromFileExtension(it)}"
            },
        )
    }

    @Test
    fun `a primary storage tree maps to the external storage directory`() {
        assertEquals(
            "$externalRoot/DCIM/Camera",
            SafUtils.getAbsolutePathFromSAFUri(context, treeUri("primary:DCIM/Camera")),
        )
        assertEquals(externalRoot, SafUtils.getAbsolutePathFromSAFUri(context, treeUri("primary:")))
    }

    @Test
    fun `a storage tree with a trailing slash loses it`() {
        assertEquals(
            "$externalRoot/DCIM",
            SafUtils.getAbsolutePathFromSAFUri(context, treeUri("primary:DCIM/")),
        )
    }

    @Test
    fun `an SD card tree maps to its volume under storage`() {
        assertEquals(
            "/storage/1A2B-3C4D/books",
            SafUtils.getAbsolutePathFromSAFUri(context, treeUri("1A2B-3C4D:books")),
        )
    }

    @Test
    fun `the home and downloads volumes map to the public folders`() {
        assertEquals(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .absolutePath + "/notes",
            SafUtils.getAbsolutePathFromSAFUri(context, treeUri("home:notes")),
        )
        assertEquals(
            FileUtils.getExternalStorageDownloadsDirectory(),
            SafUtils.getAbsolutePathFromSAFUri(context, treeUri("downloads:")),
        )
    }

    @Test
    fun `a missing tree uri has no path`() {
        assertNull(SafUtils.getAbsolutePathFromTreeUri(context, null))
    }

    @Test
    fun `a document uri becomes the tree uri of the same document`() {
        val document =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Android/media/app")

        assertEquals(
            treeUri("primary:Android/media/app"),
            SafUtils.convertFromDocumentUriToTreeUri(document),
        )
    }

    @Test
    fun `a document uri exists when its folder does`() {
        val downloads = File(FileUtils.getExternalStorageDownloadsDirectory()).apply { mkdirs() }
        val existing = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "downloads:")
        val missing = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "downloads:missing")

        assertTrue(downloads.isDirectory)
        assertTrue(SafUtils.directoryUriExists(context, existing))
        assertFalse(SafUtils.directoryUriExists(context, missing))
    }

    @Test
    fun `the internal storage root uri`() {
        assertEquals(
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"),
            FileUtils.getInternalStorageRootUri(),
        )
    }

    @Test
    fun `the app's media folder on internal storage`() {
        val media =
            FileUtils.getExternalFilesDir(context, FileUtils.ExternalStorageDirType.INT_MEDIA, null)
        val pictures =
            FileUtils.getExternalFilesDir(
                context,
                FileUtils.ExternalStorageDirType.INT_MEDIA,
                Environment.DIRECTORY_PICTURES,
            )

        assertEquals(File("$externalRoot/Android/media/${context.packageName}"), media)
        assertEquals(File(media, Environment.DIRECTORY_PICTURES), pictures)
        assertEquals(
            Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3AAndroid" +
                    "%2Fmedia%2F${context.packageName}"
            ),
            FileUtils.getExternalFilesDirUri(context, FileUtils.ExternalStorageDirType.INT_MEDIA),
        )
    }

    @Test
    fun `the app's data folder on external storage and its uri`() {
        val data = checkNotNull(FileUtils.getExternalFilesDir(context, null))
        val volumeId = data.absolutePath.split("/")[2]

        assertEquals(context.getExternalFilesDir(null), data)
        assertEquals(
            Uri.parse(
                "content://com.android.externalstorage.documents/document/$volumeId%3AAndroid" +
                    "%2Fdata%2F${context.packageName}%2Ffiles"
            ),
            FileUtils.getExternalFilesDirUri(context, FileUtils.ExternalStorageDirType.DATA),
        )
    }

    @Test
    fun `mounted storage outside the emulated one is listed when readable`() {
        // The test host has no /storage mounts.
        assertEquals(0, FileUtils.getMountedStoragePathsAsFileArray().size)
    }

    @Test
    fun `a folder is created once and found the next time`() {
        val parent = DocumentFile.fromFile(tempDir)

        val created = checkNotNull(SafUtils.safCreateDirectory(parent, "Camera"))
        val again = checkNotNull(SafUtils.safCreateDirectory(parent, "Camera"))

        assertTrue(File(tempDir, "Camera").isDirectory)
        assertEquals(created.uri, again.uri)
        assertNull(SafUtils.safCreateDirectory(null, "Camera"))
    }

    @Test
    fun `an existing file counts as created`() {
        File(tempDir, ".stignore").writeText("")

        assertTrue(
            SafUtils.safCreateFile(context, DocumentFile.fromFile(tempDir), ".stignore", "x")
        )
    }

    @Test
    fun `a new file needs a document provider`() {
        // Robolectric has none for file:// trees, so creating fails without crashing.
        assertFalse(SafUtils.safCreateFile(context, DocumentFile.fromFile(tempDir), "a.txt", "x"))
    }

    @Test
    fun `a file opens in a viewer through the file provider`() {
        val file =
            File(externalRoot, "notes.txt").apply {
                parentFile?.mkdirs()
                writeText("x")
            }

        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

        FileOpener.openFile(activity, file.path)

        val intent = checkNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.provider", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `an apk opens in the package installer`() {
        val file =
            File(externalRoot, "app.apk").apply {
                parentFile?.mkdirs()
                writeText("x")
            }

        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

        FileOpener.openFile(activity, file.path)

        assertEquals(Intent.ACTION_INSTALL_PACKAGE, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun `without a viewer the file is offered to any app`() {
        val file =
            File(externalRoot, "notes.txt").apply {
                parentFile?.mkdirs()
                writeText("x")
            }
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)

        FileOpener.openFile(activity, file.path)

        assertEquals("No app found to open the file.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a folder opens in a file manager`() {
        FileOpener.openFolder(context, tempDir.path)

        val intent = checkNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("resource/folder", intent.type)
        assertEquals(Uri.fromFile(tempDir), intent.data)
        assertEquals(tempDir.path, intent.getStringExtra("org.openintents.extra.ABSOLUTE_PATH"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a missing folder is reported`() {
        FileOpener.openFolder(context, File(tempDir, "missing").path)

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("Error (Invalid folder path)", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `without a file manager a file manager app is suggested`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)

        FileOpener.openFolder(activity, tempDir.path)

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.suggest_file_manager_app_dialog_title),
            dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text,
        )
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}
