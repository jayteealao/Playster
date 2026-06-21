package com.github.jayteealao.playster.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.screens.common.state.QuotaState
import com.github.jayteealao.playster.screens.common.state.toQuotaState

/**
 * Top-of-app banner. Observes `quota/openrouter` via [QuotaBannerViewModel]
 * and renders nothing when [QuotaState.Healthy], a sticky banner with copy
 * appropriate to the variant otherwise. All Summarize CTAs across the app
 * derive their enabled state from the same listener (see
 * `rememberQuotaState()` below) so the banner and the controls move together.
 */
@Composable
fun QuotaBanner(
    modifier: Modifier = Modifier,
    viewModel: QuotaBannerViewModel = hiltViewModel(),
) {
    val quotaDoc by viewModel.quotaDoc.collectAsStateWithLifecycle()
    val state = quotaDoc.toQuotaState()

    when (state) {
        is QuotaState.Healthy -> Box(modifier = modifier.height(0.dp))
        is QuotaState.DailyExhausted ->
            BannerBox(
                modifier = modifier,
                text = "Daily summary limit reached. Resets at midnight UTC.",
            )
        is QuotaState.PerMinuteExhausted ->
            BannerBox(
                modifier = modifier,
                text = "Rate limited — try again in a moment.",
            )
    }
}

@Composable
private fun BannerBox(
    modifier: Modifier,
    text: String,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("quota-banner"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * Shared Composable accessor so any screen can derive [QuotaState] from the
 * same listener. Renders nothing; returns the current state for caller logic.
 */
@Composable
fun rememberQuotaState(viewModel: QuotaBannerViewModel = hiltViewModel()): QuotaState {
    val quotaDoc by viewModel.quotaDoc.collectAsStateWithLifecycle()
    return quotaDoc.toQuotaState()
}
