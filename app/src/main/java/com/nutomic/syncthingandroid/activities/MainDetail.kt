package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The panes for the current window: two from expanded windows, avoiding a separating hinge. */
@Composable
internal fun paneDirective(): PaneScaffoldDirective =
    calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())

/** How much the detail pane shrinks at the end of a back gesture. */
private const val BACK_SHRINK = 0.1f

/** The pane shrinks towards its start edge, the list's side. */
private val BACK_SHRINK_ORIGIN = TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)

/** Whether the window has room for a list and a detail pane side by side. */
internal val PaneScaffoldDirective.twoPanes: Boolean
    get() = maxHorizontalPartitions > 1

/**
 * [list] alone, or with [detail] next to it when the window has room ([directive]); the panes can
 * be resized with a drag handle.
 */
@Composable
internal fun ListWithDetail(
    directive: PaneScaffoldDirective,
    detail: Detail?,
    modifier: Modifier = Modifier,
    backProgress: () -> Float = { 0f },
    /** What the detail pane shows while nothing is open in it. */
    placeholder: @Composable () -> Unit = {},
    list: @Composable () -> Unit,
) {
    // The same list content in both layouts, so it keeps its state (scroll) when a pane opens.
    val currentList by rememberUpdatedState(list)
    val movableList = remember { movableContentOf { currentList() } }
    if (!directive.twoPanes) {
        Box(modifier = modifier) { movableList() }
        return
    }
    ListDetailPaneScaffold(
        directive = directive,
        value =
            calculateThreePaneScaffoldValue(
                maxHorizontalPartitions = directive.maxHorizontalPartitions,
                adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
                currentDestination =
                    detail?.let {
                        ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, it.key)
                    },
            ),
        modifier = modifier,
        listPane = { AnimatedPane { movableList() } },
        detailPane = {
            if (detail == null) {
                AnimatedPane { placeholder() }
                return@ListDetailPaneScaffold
            }
            AnimatedPane { DetailPane(detail, backProgress) }
        },
        paneExpansionDragHandle = { state ->
            val interactionSource = remember { MutableInteractionSource() }
            VerticalDragHandle(
                modifier =
                    Modifier.paneExpansionDraggable(
                        state,
                        LocalMinimumInteractiveComponentSize.current,
                        interactionSource,
                    ),
                interactionSource = interactionSource,
            )
        },
    )
}

/** A folder or device in its pane: it shrinks towards the list while back closes it. */
@Composable
internal fun DetailPane(detail: Detail, backProgress: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    val scale = 1f - BACK_SHRINK * backProgress()
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = BACK_SHRINK_ORIGIN
                }
                .testTag("detailPane")
                // The scaffold focuses the pane it opens; the pane keeps that focus itself, or its
                // first field would take it and bring up the keyboard.
                .focusTarget()
    ) {
        // Each folder or device is a new screen: effects and saved state must not carry over.
        key(detail.key) { DetailContent(detail) }
    }
}

/** The detail pane while nothing is open: an icon and what to choose in the list. */
@Composable
internal fun DetailPlaceholder(icon: ImageVector, text: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier.size(PLACEHOLDER_BADGE.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialShapes.Cookie9Sided.toShape(),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PLACEHOLDER_ICON.dp),
                )
            }
            Text(
                stringResource(text),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("detailPlaceholder"),
            )
        }
    }
}

private const val PLACEHOLDER_BADGE = 96
private const val PLACEHOLDER_ICON = 40

@Composable
private fun DetailContent(detail: Detail) {
    val callbacks = detail.callbacks
    when (detail) {
        is Detail.FolderDetail -> {
            val viewModel = detail.viewModel
            val controller = viewModel.controller ?: return
            EditEffects(
                viewModel.events,
                FolderRequest.entries,
                EditCallbacks(viewModel::onResult, callbacks.onClose, callbacks.onLeave),
                viewModel::refreshWhileShown,
            )
            controller.form?.let {
                FolderEditScreen(
                    form = it,
                    info = controller.info,
                    dialog = controller.dialog,
                    actions = controller.actions,
                    inPane = true,
                )
            }
        }
        is Detail.DeviceDetail -> {
            val viewModel = detail.viewModel
            val controller = viewModel.controller ?: return
            EditEffects(
                viewModel.events,
                DeviceRequest.entries,
                EditCallbacks(
                    viewModel::onResult,
                    callbacks.onClose,
                    callbacks.onLeave,
                    callbacks.onShowDeviceId,
                ),
                viewModel::refreshWhileShown,
            )
            controller.form?.let {
                DeviceEditScreen(
                    form = it,
                    info = controller.info,
                    dialog = controller.dialog,
                    actions = controller.actions,
                    inPane = true,
                )
            }
        }
    }
}
