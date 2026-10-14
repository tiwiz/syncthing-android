package com.nutomic.syncthingandroid.activities

import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants

/** Picks the type of a folder for the folder screen. */
class FolderTypeDialogActivity : SingleChoiceDialogActivity() {

    override val values =
        listOf(
            Constants.FOLDER_TYPE_SEND_RECEIVE,
            Constants.FOLDER_TYPE_SEND_ONLY,
            Constants.FOLDER_TYPE_RECEIVE_ONLY,
            Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED,
        )
    override val labels = R.array.folder_types
    override val extraValue = EXTRA_FOLDER_TYPE
    override val extraResult = EXTRA_RESULT_FOLDER_TYPE

    companion object {
        const val EXTRA_FOLDER_TYPE = ".activities.FolderTypeDialogActivity.FOLDER_TYPE"
        const val EXTRA_RESULT_FOLDER_TYPE =
            ".activities.FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE"
    }
}
