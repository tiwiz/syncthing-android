package com.nutomic.syncthingandroid.settings

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
sealed interface SettingsRoute : NavKey {

    @Serializable
    data object Root : SettingsRoute

    @Serializable
    data object RunConditions : SettingsRoute
    @Serializable
    data object UserInterface : SettingsRoute
    @Serializable
    data object Behavior : SettingsRoute
    @Serializable
    data object SyncthingOptions : SettingsRoute
    @Serializable
    data object CustomCertificate : SettingsRoute
    @Serializable
    data object ImportExport : SettingsRoute
    @Serializable
    data object Troubleshooting : SettingsRoute
    @Serializable
    data object Experimental : SettingsRoute
    @Serializable
    data object About : SettingsRoute
    @Serializable
    data object Licenses : SettingsRoute


    companion object {
        private const val TAG = "SettingsRoute"

        // Use these strings to open particular screen directly
        fun fromString(route: String?): SettingsRoute = when (route) {
            "RunConditions" -> RunConditions
            "UserInterface" -> UserInterface
            "Behavior" -> Behavior
            "SyncthingOptions" -> SyncthingOptions
            "CustomCertificate" -> CustomCertificate
            "ImportExport" -> ImportExport
            "Troubleshooting" -> Troubleshooting
            "Experimental" -> Experimental
            "About" -> About
            "Licenses" -> Licenses
            "Root" -> Root
            else -> {
                Log.d(TAG, "Unknown settings path provided: $route. Defaulting to Root.")
                Root
            }
        }
    }
}

interface Navigator<T: NavKey> {
    fun navigateTo(route: T)
    fun navigateBack()
    fun navigateUp() // added temporarily to finish settings activity and return
}

@Composable
fun rememberSettingsNavBackStack(startDestination: SettingsRoute): NavBackStack<SettingsRoute> {
    return rememberSerializable(
        serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer())
    ) {
        val initialRouts = listOfNotNull(
            SettingsRoute.Root,
            SettingsRoute.About.takeIf { startDestination == SettingsRoute.Licenses },
            startDestination.takeIf { it != SettingsRoute.Root }
        ).toMutableStateList()
        NavBackStack(initialRouts)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsNavDisplay(
    backStack: NavBackStack<SettingsRoute>,
    navigator: Navigator<SettingsRoute>,
    modifier: Modifier = Modifier,
) {
    val entryProvider = remember(navigator) {
        entryProvider {
            settingsRootEntry(navigator)
            settingsRunConditionsEntry(navigator)
            settingsUserInterfaceEntry(navigator)
            settingsBehaviorEntry(navigator)
            settingsSyncthingOptionsEntry(navigator)
            settingsCustomCertificateEntry(navigator)
            settingsImportExportEntry(navigator)
            settingsTroubleshootingEntry(navigator)
            settingsExperimentalEntry(navigator)
            settingsAboutEntry(navigator)
            licensesEntry(navigator)
        }
    }
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val navigatedModifier = modifier.onKeyEvent { keyEvent ->
        if (keyEvent.key == Key.DirectionLeft
            && keyEvent.type == KeyEventType.KeyDown) {
            navigator.navigateBack()
            true
        } else {
            false
        }
    }
    if (directive.twoPanes) {
        SettingsListDetail(
            backStack = backStack,
            navigator = navigator,
            entryProvider = entryProvider,
            modifier = navigatedModifier,
        )
    } else {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.navigateBack() },
            entryDecorators = settingsEntryDecorators(),
            entryProvider = entryProvider,
            transitionSpec = { forwardTransition() },
            popTransitionSpec = { backwardTransition() },
            predictivePopTransitionSpec = { _ -> predictiveBackwardTransition() },
            modifier = navigatedModifier,
        )
    }
}

/** Whether the window has room for a list and a detail pane side by side. */
private val PaneScaffoldDirective.twoPanes: Boolean
    get() = maxHorizontalPartitions > 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsListDetail(
    backStack: NavBackStack<SettingsRoute>,
    navigator: Navigator<SettingsRoute>,
    entryProvider: (SettingsRoute) -> NavEntry<SettingsRoute>,
    modifier: Modifier = Modifier,
) {
    val detailRoute = backStack.lastOrNull().takeIf { it != SettingsRoute.Root }
    if (detailRoute == null) {
        SettingsRootRoute(navigator = navigator, modifier = modifier)
        return
    }
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    ListDetailPaneScaffold(
        directive = directive,
        value = calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            currentDestination =
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, detailRoute),
        ),
        modifier = modifier,
        listPane = {
            AnimatedPane {
                SettingsRootRoute(navigator = navigator)
            }
        },
        detailPane = {
            AnimatedPane {
                val detailBackStack = remember(detailRoute) { mutableStateListOf(detailRoute) }
                NavDisplay(
                    backStack = NavBackStack(detailBackStack),
                    onBack = { navigator.navigateBack() },
                    entryDecorators = settingsEntryDecorators(),
                    entryProvider = entryProvider,
                    transitionSpec = { forwardTransition() },
                    popTransitionSpec = { backwardTransition() },
                    predictivePopTransitionSpec = { _ -> predictiveBackwardTransition() },
                )
            }
        },
    )
}

/**
 * Each entry keeps its saveable state and gets its own ViewModels, cleared when the entry leaves
 * the back stack.
 */
@Composable
private fun settingsEntryDecorators(): List<NavEntryDecorator<SettingsRoute>> =
    listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    )

private fun AnimatedContentTransitionScope<*>.forwardTransition(): ContentTransform =
    slideInHorizontally(initialOffsetX = { it }) togetherWith
            slideOutHorizontally(targetOffsetX = { -it })

private fun AnimatedContentTransitionScope<*>.backwardTransition(): ContentTransform =
    slideInHorizontally(initialOffsetX = { -it }) togetherWith
            slideOutHorizontally(targetOffsetX = { it })

private fun AnimatedContentTransitionScope<*>.predictiveBackwardTransition(): ContentTransform =
    slideInHorizontally(initialOffsetX = { fullWidth -> -fullWidth }) togetherWith
            slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth })
