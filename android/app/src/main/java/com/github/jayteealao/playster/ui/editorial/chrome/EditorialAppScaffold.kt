package com.github.jayteealao.playster.ui.editorial.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.github.jayteealao.playster.navigation.EditorialRoutes
import com.github.jayteealao.playster.navigation.EditorialTab
import com.github.jayteealao.playster.navigation.navigateToEditorialRoot
import com.github.jayteealao.playster.screens.common.rememberQuotaState
import com.github.jayteealao.playster.screens.common.state.QuotaState
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialBottomNav
import com.github.jayteealao.playster.ui.editorial.components.EditorialQuotaNotice

/**
 * The editorial app chrome: a full-bleed paper field with the quota band at
 * the top and the bottom navigation under the content — no Material
 * Scaffold, no elevation, just the paper and its rules. The paper runs
 * edge-to-edge under transparent system bars (see [applyEditorialSystemBars]);
 * content is held below the status bar and the bottom nav sits above the
 * gesture area.
 *
 * On the auth route all chrome hides — sign-in is a cover page, not a
 * tabbed section. Everywhere else the active tab derives from the current
 * back-stack entry via [EditorialRoutes.tabForRoute], and taps navigate with
 * the state-preserving root pattern ([navigateToEditorialRoot]).
 *
 * The root carries `testTagsAsResourceId` so every `Modifier.testTag` in
 * the tree surfaces as a resource-id for UI-automation `id:` selectors —
 * the selector convention the repo's flows standardize on.
 */
@OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId — the documented UiAutomator/Maestro interop seam
@Composable
fun EditorialAppScaffold(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val activeTab = EditorialRoutes.tabForRoute(route)
    val chromeHidden = route == EditorialRoutes.AUTH

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.palette.paper)
                .semantics { testTagsAsResourceId = true },
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        if (!chromeHidden) {
            QuotaNoticeBand()
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
        if (!chromeHidden) {
            EditorialBottomNav(
                items = EditorialTab.entries.map { it.label },
                selectedIndex = activeTab?.ordinal ?: NO_ACTIVE_TAB,
                onSelect = { index ->
                    navController.navigateToEditorialRoot(
                        EditorialRoutes.rootRouteFor(EditorialTab.entries[index]),
                    )
                },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        } else {
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/**
 * The quota banner idiom carried into the editorial chrome: the same
 * `quota/openrouter` listener every Summarize CTA derives from (via
 * [rememberQuotaState]), rendered as the rule-bounded paperDeep band.
 * Healthy renders nothing. Keeps the `quota-banner` test tag so existing
 * quota flows re-target without a selector change.
 */
@Composable
private fun QuotaNoticeBand() {
    val message =
        when (rememberQuotaState()) {
            is QuotaState.Healthy -> null
            is QuotaState.DailyExhausted -> "Daily summary limit reached. Resets at midnight UTC."
            is QuotaState.PerMinuteExhausted -> "Rate limited — try again in a moment."
        }
    if (message != null) {
        EditorialQuotaNotice(
            message = message,
            modifier = Modifier.testTag("quota-banner"),
        )
    }
}

private const val NO_ACTIVE_TAB = -1
