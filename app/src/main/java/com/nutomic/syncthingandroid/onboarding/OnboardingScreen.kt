package com.nutomic.syncthingandroid.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * The list of onboarding pages shown in order.
 */

@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onFinishOnboarding: () -> Unit,
    onGrantLocationPermission: () -> Unit,
    onGrantLocalNetworkPermission: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { uiState.pages.size })

    BackHandler(onBack = onBack)

    LaunchedEffect(uiState.currentPage) {
        if (uiState.currentPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(uiState.currentPage)
        }
    }
    // The pager only follows the flow. On an Android XR headset it settled on the last page half a
    // second after start, with no scroll call and no size change, for a reason not found; a window
    // laid out at zero width first does the same. Whenever it is at rest away from the flow's page
    // and not already heading there, put it back.
    val currentPage by rememberUpdatedState(uiState.currentPage)
    val hasWidth by remember { derivedStateOf { pagerState.layoutInfo.viewportSize.width > 0 } }
    LaunchedEffect(pagerState) {
        snapshotFlow {
                Triple(pagerState.settledPage, pagerState.isScrollInProgress, hasWidth)
            }
            .collect { (page, scrolling, width) ->
                if (width && !scrolling && page != currentPage && pagerState.targetPage != currentPage) {
                    pagerState.scrollToPage(currentPage)
                }
            }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) { pageIdx ->
        val page = uiState.pages[pageIdx]
        OnboardingPage(
            page = page,
            uiState = uiState,
            pageIndex = pageIdx,
            requestTvFocus = pagerState.currentPage == pageIdx && !pagerState.isScrollInProgress,
            onBack = onBack,
            onContinue = onContinue,
            onFinishOnboarding = onFinishOnboarding,
            onGrantLocationPermission = onGrantLocationPermission,
            onGrantLocalNetworkPermission = onGrantLocalNetworkPermission,
            onGrantNotificationPermission = onGrantNotificationPermission,
        )
    }
}
