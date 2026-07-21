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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.github.jayteealao.playster.navigation.EditorialRoutes
import com.github.jayteealao.playster.navigation.EditorialTab
import com.github.jayteealao.playster.navigation.navigateToEditorialRoot
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialBottomNav

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
 *
 * `quotaBand` is a slot, not a hard dependency: `ui.editorial` is the
 * screens-agnostic design-system/chrome layer and must stay composable
 * without Hilt, so the scaffold itself never reaches into `screens.*` for
 * quota state. The composition root supplies the real, Hilt-backed band
 * (see [com.github.jayteealao.playster.screens.common.QuotaNoticeBand]);
 * the neutral empty default keeps the scaffold renderable on its own.
 */
@OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId — the documented UiAutomator/Maestro interop seam
@Composable
fun EditorialAppScaffold(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    quotaBand: @Composable () -> Unit = {},
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
            quotaBand()
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

private const val NO_ACTIVE_TAB = -1
