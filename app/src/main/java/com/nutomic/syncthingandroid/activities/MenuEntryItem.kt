package com.nutomic.syncthingandroid.activities

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

/** One overflow entry; the menu and the expanded rail show the same list. */
internal class MenuEntryItem(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val enabled: Boolean,
    /** A divider goes above this entry. */
    val apart: Boolean = false,
    val onClick: () -> Unit,
)
