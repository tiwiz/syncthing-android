package com.nutomic.syncthingandroid.testutil

import android.content.Context
import com.nutomic.syncthingandroid.service.SyncthingService
import org.robolectric.shadows.ShadowStatFs

/** Exports the backup and waits for the result, running the main looper meanwhile. */
fun SyncthingService.exportAndWait(): Boolean = awaitResult(::exportConfig)

/** Imports the backup and waits for the result, running the main looper meanwhile. */
fun SyncthingService.importAndWait(): Boolean = awaitResult(::importConfig)

private fun awaitResult(start: ((Boolean) -> Unit) -> Unit): Boolean {
    var result: Boolean? = null
    start { result = it }
    awaitMain { result != null }
    return checkNotNull(result)
}

/**
 * Gives [Context.getFilesDir] 1 GiB of free space: Robolectric's `StatFs` reports none for a path
 * nobody registered, and an import checks that the backup fits before extracting it.
 */
fun registerFreeSpace(context: Context) {
    val blocks = FREE_SPACE_BYTES / ShadowStatFs.BLOCK_SIZE
    ShadowStatFs.registerStats(context.filesDir, blocks.toInt(), blocks.toInt(), blocks.toInt())
}

private const val FREE_SPACE_BYTES = 1L shl 30
