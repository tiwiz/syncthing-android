package com.nutomic.syncthingandroid.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    // The pager only follows the flow: whatever moves it elsewhere (a window laid out at zero width
    // first, or the jump to the last page seen on Android XR), it goes back to the flow's page once
    // it has a size and is not moving.
    val currentPage by rememberUpdatedState(uiState.currentPage)
    LaunchedEffect(pagerState) {
        snapshotFlow {
                Triple(
                    pagerState.currentPage,
                    pagerState.isScrollInProgress,
                    pagerState.layoutInfo.viewportSize.width,
                )
            }
            .collect { (page, scrolling, width) ->
                if (width > 0 && !scrolling && page != currentPage) {
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
