package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.SectionHeader
import com.nutomic.syncthingandroid.ui.StateBadge
import com.nutomic.syncthingandroid.ui.groupedItemShape

// The main screen's status destination: what Syncthing does, the run mode and the figures.

private const val TILE_CORNER = 20
private const val HERO_BADGE_SIZE = 72

/** The run modes, in the order of the Constants.BTNSTATE_* values. */
private val RUN_MODES =
    listOf(R.string.run_mode_automatic, R.string.run_mode_run, R.string.run_mode_stop)

@Composable
internal fun StatusTab(status: StatusInfo?, forceStartStop: Int, actions: MainActions) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
    ) {
        ListTitle(MainTab.STATUS)
        status?.let { StatusHero(it) }
        SectionHeader(stringResource(R.string.run_mode_title))
        RunModeButtons(forceStartStop, actions)
        Text(
            stringResource(R.string.run_mode_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (!status?.reasons.isNullOrEmpty()) {
            SectionHeader(stringResource(R.string.run_conditions_title))
            status.reasons.forEachIndexed { index, reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                groupedItemShape(index, status.reasons.size),
                            )
                            .padding(16.dp)
                            .testTag("statusReason"),
                )
            }
        }
        if (!status?.figures.isNullOrEmpty()) {
            Spacer(Modifier.height(16.dp))
            StatusFigures(status.figures)
        }
    }
}

/** What Syncthing is doing, with its state badge or a loading indicator while starting. */
@Composable
internal fun StatusHero(status: StatusInfo, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(CARD_CORNER.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (status.starting) {
                LoadingIndicator(
                    modifier = Modifier.size(HERO_BADGE_SIZE.dp).testTag("statusLoading")
                )
            } else {
                StateBadge(
                    status.tone,
                    size = HERO_BADGE_SIZE.dp,
                    modifier = Modifier.testTag("statusBadge"),
                )
            }
            Text(
                status.headline,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.testTag("statusHeadline"),
            )
        }
    }
}

/** Follow the run conditions, force Syncthing to run or to stop: a connected button group. */
@Composable
private fun RunModeButtons(forceStartStop: Int, actions: MainActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        RUN_MODES.forEachIndexed { index, label ->
            val checked = index == forceStartStop
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) actions.onForceStartStop(index) },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        RUN_MODES.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                modifier =
                    Modifier.weight(1f).testTag("force:$index").semantics {
                        role = Role.RadioButton
                        selected = checked
                    },
            ) {
                // No icons: with one, "Automatic" does not fit a third of a phone.
                Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Uptime, memory, rates and announce servers, two tiles per row. */
@Composable
private fun StatusFigures(figures: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in figures.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in pair) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(TILE_CORNER.dp),
                        modifier = Modifier.weight(1f).testTag("statusFigure"),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(value, style = MaterialTheme.typography.titleMediumEmphasized)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
