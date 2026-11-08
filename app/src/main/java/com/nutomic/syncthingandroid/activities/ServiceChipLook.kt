package com.nutomic.syncthingandroid.activities

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.StateBadge
import com.nutomic.syncthingandroid.views.StateTone

/** How the top bar shows the service state; [tag] keeps the three lights tests look for. */
internal data class ServiceChipLook(
    val tone: StateTone,
    @param:StringRes val label: Int,
    val tag: String,
)

internal fun serviceChipLook(state: SyncthingService.State): ServiceChipLook =
    when (state) {
        SyncthingService.State.ACTIVE ->
            ServiceChipLook(StateTone.OK, R.string.syncthing_active, "serviceChip:ACTIVE")
        SyncthingService.State.STARTING ->
            ServiceChipLook(StateTone.BUSY, R.string.fork_service_starting, "serviceChip:STARTING")
        SyncthingService.State.ERROR ->
            ServiceChipLook(StateTone.ERROR, R.string.state_error, "serviceChip:DISABLED")
        SyncthingService.State.INIT,
        SyncthingService.State.DISABLED ->
            ServiceChipLook(StateTone.OFFLINE, R.string.syncthing_disabled, "serviceChip:DISABLED")
    }

private const val BADGE_SIZE = 24

/** On phones the badge takes the old dot's room (28 dp), so the app name stays on one line. */
private const val COMPACT_BADGE_SIZE = 20

/**
 * The service state: the state's shape and icon, plus its word from medium width on. Screen readers
 * hear the word at every width.
 */
@Composable
internal fun ServiceChip(state: SyncthingService.State, modifier: Modifier = Modifier) {
    val look = serviceChipLook(state)
    val label = stringResource(look.label)
    val wide =
        currentWindowAdaptiveInfoV2()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val semantics = modifier.testTag(look.tag).clearAndSetSemantics { contentDescription = label }
    if (wide) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = semantics.padding(horizontal = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateBadge(look.tone, size = BADGE_SIZE.dp)
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    } else {
        StateBadge(
            look.tone,
            size = COMPACT_BADGE_SIZE.dp,
            modifier = semantics.padding(horizontal = 4.dp),
        )
    }
}
