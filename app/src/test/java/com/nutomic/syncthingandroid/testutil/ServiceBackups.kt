package com.nutomic.syncthingandroid.testutil

import com.nutomic.syncthingandroid.service.SyncthingService

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
