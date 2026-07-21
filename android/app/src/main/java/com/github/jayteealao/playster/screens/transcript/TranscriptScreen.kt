package com.github.jayteealao.playster.screens.transcript

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.screens.player.playback.PlaybackState

/**
 * Transcript — the reader's article over the live recording. The mock's Masthead
 * strip keeps the shared embed visible while the timestamped paragraphs read as
 * prose; the active line follows the player clock, a tap seeks, a long-press
 * underlines, and the AppBar's note affordance marks the moment. Everything is a
 * component-library primitive plus the one WebView surface, so the screen is
 * composition; playback is the activity-shared [com.github.jayteealao.playster.screens.player.playback.PlaybackSession]
 * the Player and Transcript both render.
 */
@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val playbackState by viewModel.controller.state.collectAsStateWithLifecycle()
    // Held as State (not read at this level) so the per-second position tick never
    // recomposes the screen — only the derived active line, the pill, and the band.
    val positionState = viewModel.controller.positionSeconds.collectAsStateWithLifecycle()
    val playing = playbackState is PlaybackState.Playing

    TranscriptContent(
        state = state,
        header = header,
        position = positionState,
        playing = playing,
        onBack = onBack,
        onOpenPlayer = onOpenPlayer,
        onSeek = viewModel::seekTo,
        onToggleHighlight = viewModel::toggleHighlight,
        onPlayPause = { viewModel.togglePlayPause(playing) },
        onCreateNote = viewModel::createNoteAt,
        embedSlot = {
            TranscriptEmbed(
                session = viewModel.playbackSession,
                playbackState = playbackState,
                positionLabel = formatClock(positionState.value),
                onOpenPlayer = onOpenPlayer,
            )
        },
        modifier = modifier,
    )
}
