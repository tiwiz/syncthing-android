package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag

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
    list: @Composable () -> Unit,
) {
    // The same list content in both layouts, so it keeps its state (scroll) when a pane opens.
    val currentList by rememberUpdatedState(list)
    val movableList = remember { movableContentOf { currentList() } }
    if (detail == null || !directive.twoPanes) {
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
                    ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, detail.key),
            ),
        modifier = modifier,
        listPane = { AnimatedPane { movableList() } },
        detailPane = {
            AnimatedPane {
                Box(
                    modifier =
                        Modifier.graphicsLayer {
                                // Shrinks towards the list while a back gesture closes the pane.
                                val scale = 1f - BACK_SHRINK * backProgress()
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = BACK_SHRINK_ORIGIN
                            }
                            .testTag("detailPane")
                            // The scaffold focuses the pane it opens; the pane keeps that focus
                            // itself, or its first field would take it and bring up the keyboard.
                            .focusTarget()
                ) {
                    DetailContent(detail)
                }
            }
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
                )
            }
        }
    }
}
