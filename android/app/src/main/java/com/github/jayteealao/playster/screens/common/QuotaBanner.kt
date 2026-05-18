package com.github.jayteealao.playster.screens.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * No-op placeholder reserved by slice 1. Slice 4 ("summary-ui") replaces this
 * with the real implementation that observes `quota/openrouter` and renders
 * a banner when the daily/per-minute quota is exhausted.
 *
 * Reserving the Composable here keeps the navigation graph and PlaylistScreen
 * layout stable between slices — slice 4 only changes the body of this file.
 */
@Composable
fun QuotaBanner(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.height(0.dp))
}
