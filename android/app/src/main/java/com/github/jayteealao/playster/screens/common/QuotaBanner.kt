package com.github.jayteealao.playster.screens.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.screens.common.state.QuotaState
import com.github.jayteealao.playster.screens.common.state.toQuotaState
import com.github.jayteealao.playster.ui.editorial.components.EditorialQuotaNotice

/**
 * Shared Composable accessor so any screen can derive [QuotaState] from the
 * same listener. Renders nothing; returns the current state for caller logic.
 */
@Composable
fun rememberQuotaState(viewModel: QuotaBannerViewModel = hiltViewModel()): QuotaState {
    val quotaDoc by viewModel.quotaDoc.collectAsStateWithLifecycle()
    return quotaDoc.toQuotaState()
}

/**
 * The quota banner idiom carried into the editorial chrome: the same
 * `quota/openrouter` listener every Summarize CTA derives from (via
 * [rememberQuotaState]), rendered as the rule-bounded paperDeep band.
 * Healthy renders nothing. Keeps the `quota-banner` test tag so existing
 * quota flows re-target without a selector change.
 *
 * Lives here (not in `ui.editorial.chrome`) because it is
 * Hilt-ViewModel-backed: `ui.editorial` is the screens-agnostic
 * design-system/chrome layer and must stay composable without Hilt. The
 * composition root ([com.github.jayteealao.playster.MainActivity]) supplies
 * this as the `quotaBand` slot to
 * [com.github.jayteealao.playster.ui.editorial.chrome.EditorialAppScaffold],
 * keeping that dependency out of the chrome layer itself.
 */
@Composable
fun QuotaNoticeBand() {
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
