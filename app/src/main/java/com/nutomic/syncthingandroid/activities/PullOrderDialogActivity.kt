package com.nutomic.syncthingandroid.activities

import com.nutomic.syncthingandroid.R

/** Picks the order in which a folder pulls files, for the folder screen. */
class PullOrderDialogActivity : SingleChoiceDialogActivity() {

    override val values =
        listOf(
            "random",
            "alphabetic",
            "smallestFirst",
            "largestFirst",
            "oldestFirst",
            "newestFirst",
        )
    override val labels = R.array.pull_order_types
    override val extraValue = EXTRA_PULL_ORDER
    override val extraResult = EXTRA_RESULT_PULL_ORDER

    companion object {
        const val EXTRA_PULL_ORDER = ".activities.PullOrderDialogActivity.PULL_ORDER"
        const val EXTRA_RESULT_PULL_ORDER =
            ".activities.PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER"
    }
}
