package com.github.jayteealao.playster.screens.player

import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState

/**
 * The Player screen's sealed state, JVM-pure (no Compose, Firestore, or playback
 * types) so [PlayerStateAssembler] is device-free unit-testable and the sealed
 * states render under Robolectric goldens. Live playback — position, playback
 * state, panel expansion, the NOW-badge index — is layered on top by the screen
 * from the [com.github.jayteealao.playster.screens.player.playback.PlaybackController]
 * streams; this model carries only what the data sources decide.
 */
sealed interface PlayerUiState {
    /** The video document hasn't resolved yet. */
    data object Loading : PlayerUiState

    /** A source flow terminated with an error. */
    data object Error : PlayerUiState

    /** The assembled article-page player over real data. */
    data class Content(
        val videoId: String,
        val playlistId: String,
        val header: PlayerHeader,
        val summary: SummaryUiState,
        val chapters: List<ChapterEntry>,
        val notes: List<PlayerNote>,
        val resumeSeconds: Float,
        val folioLeft: String,
        val folioRight: String,
    ) : PlayerUiState
}

/** The article header: kicker (episode wayfinding), title, channel byline, meta line. */
data class PlayerHeader(
    val kicker: String,
    val title: String,
    val byline: String,
    val meta: String,
)

/** One chapter row: its start-time label, title, and mm:ss duration (blank for the final). */
data class ChapterEntry(
    val timeLabel: String,
    val label: String,
    val durationLabel: String,
    val startSeconds: Float,
)

/** One note row: the accent "AT mm:ss" timestamp and the serif note text. */
data class PlayerNote(
    val timestampLabel: String,
    val text: String,
)
