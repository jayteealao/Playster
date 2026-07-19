package com.github.jayteealao.playster.screens.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Search — the mock's single italic-serif field over the hybrid engine: instant
 * network-free title matches, the debounced backend transcript leg with its own
 * sub-state, local recent-search pills, and the jump-to-timestamp deep link into
 * the Transcript. Pure composition over [SearchContent]; the ViewModel owns the
 * two legs and the recents store.
 */
@Composable
fun SearchScreen(
    onOpenTranscriptAt: (videoId: String, startSeconds: Double) -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onRecentTap = viewModel::onRecentTap,
        onOpenTranscriptAt = onOpenTranscriptAt,
        onOpenPlayer = onOpenPlayer,
        onOpenPlaylist = onOpenPlaylist,
        modifier = modifier,
    )
}
