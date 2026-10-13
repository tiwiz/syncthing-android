package com.nutomic.syncthingandroid.util

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope

// Work that must outlive a screen (writing config, exports) runs in the activity's scope; the
// activities provide it once instead of every composable taking it as a parameter.
@Suppress("CompositionLocalAllowlist")
val LocalActivityScope = staticCompositionLocalOf<CoroutineScope> {
    error("No activity scope provided")
}
