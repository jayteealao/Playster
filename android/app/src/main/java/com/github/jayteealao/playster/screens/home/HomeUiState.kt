package com.github.jayteealao.playster.screens.home

import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.VideoDoc
import java.time.Clock

/**
 * The Home screen's sealed state. Deliberately JVM-pure — no Compose or
 * Firestore types leak in — so the whole assembly is device-free unit-testable
 * ([HomeState.assemble]) and the gap states render under Robolectric goldens.
 */
sealed interface HomeUiState {
    /** First snapshot pending. */
    data object Loading : HomeUiState

    /** A brand-new account: no playlists and no progress. The editorial first-run notice. */
    data object Empty : HomeUiState

    /** A flow terminated with an error (e.g. a Firestore listener failure). */
    data object Error : HomeUiState

    /** The assembled front page. */
    data class Content(
        val masthead: Masthead,
        val headliner: Headliner?,
        val shelf: List<ShelfEntry>,
    ) : HomeUiState
}

/** The masthead block: issue line, unread aggregate, publication title, deck. */
data class Masthead(
    val issueLine: String,
    val unreadLabel: String,
    val titleTop: String,
    val titleEmphasis: String,
    val deck: String,
)

/** The continue-listening headliner — the most recently touched video. */
data class Headliner(
    val videoId: String,
    val episodeLabel: String,
    val title: String,
    val meta: String,
    val positionLabel: String,
    val durationLabel: String,
    val progress: Float,
)

/** One dressed shelf row. [progress] is null when hidden (≤2%). */
data class ShelfEntry(
    val playlistId: String,
    val ordinal: Int,
    val kicker: String,
    val title: String,
    val byline: String,
    val progress: Float?,
)

/**
 * Pure assembly of the Home state from the four data sources, routed through
 * [EditorialDressing]. Total and side-effect-free: this is where AC2's shelf
 * ordering, headliner selection, and progress-hiding logic live, and where
 * [HomeViewModelTest]-shaped assertions bite without a device.
 */
object HomeState {
    fun assemble(
        playlists: List<PlaylistDoc>,
        shelfProgress: List<ProgressDoc>,
        videoProgress: List<ProgressDoc>,
        continueHeadliner: Pair<ProgressDoc, VideoDoc?>?,
        clock: Clock,
    ): HomeUiState {
        val nothingSeeded =
            listOf(playlists, shelfProgress, videoProgress).all { it.isEmpty() } && continueHeadliner == null
        if (nothingSeeded) return HomeUiState.Empty

        val byId = playlists.associateBy { it.id }
        // Volumes number against a stable order (publishedAt asc, id tiebreak) so a
        // volume label doesn't renumber when the shelf reorders by last-opened.
        val stableOrder = playlists.sortedWith(compareBy({ it.publishedAt }, { it.id }))
        val videoByPlaylist = videoProgress.groupBy { it.playlistId }

        // Opened playlists in the query's last-opened order, then the never-
        // opened tail by publishedAt desc (a deterministic plan choice — the AC
        // only pins the opened head).
        val openedIds = shelfProgress.map { it.playlistId }
        val openedEntries = openedIds.mapNotNull { byId[it] }
        val openedIdSet = openedIds.toSet()
        val neverOpened =
            playlists
                .filter { it.id !in openedIdSet }
                .sortedWith(compareByDescending<PlaylistDoc> { it.publishedAt }.thenBy { it.id })
        val ordered = openedEntries + neverOpened

        val shelf =
            ordered.mapIndexed { index, playlist ->
                ShelfEntry(
                    playlistId = playlist.id,
                    ordinal = index + 1,
                    kicker =
                        "${EditorialDressing.tagLabel(playlist)} · " +
                            EditorialDressing.volumeLabel(playlist, stableOrder),
                    title = playlist.title,
                    byline =
                        "by ${playlist.channelTitle} · ${playlist.videoCount} videos · " +
                            EditorialDressing.hoursLabel(playlist),
                    progress = EditorialDressing.shelfProgress(videoByPlaylist[playlist.id].orEmpty()),
                )
            }

        val masthead =
            Masthead(
                issueLine = "Issue ${EditorialDressing.issueNumber(clock)} · ${EditorialDressing.dateLabel(clock)}",
                unreadLabel = EditorialDressing.unreadHoursLabel(playlists, videoByPlaylist),
                titleTop = EditorialDressing.MASTHEAD_TITLE_TOP,
                titleEmphasis = EditorialDressing.MASTHEAD_TITLE_EMPHASIS,
                deck = EditorialDressing.mastheadDeck(openedEntries.size, neverOpened.size),
            )

        return HomeUiState.Content(
            masthead = masthead,
            headliner = buildHeadliner(continueHeadliner?.first, continueHeadliner?.second),
            shelf = shelf,
        )
    }

    private fun buildHeadliner(
        progress: ProgressDoc?,
        video: VideoDoc?,
    ): Headliner? {
        if (progress == null || video == null) return null
        val remaining = (progress.durationSeconds - progress.positionSeconds).coerceAtLeast(0L)
        val fraction =
            if (progress.durationSeconds > 0L) {
                (progress.positionSeconds.toFloat() / progress.durationSeconds.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        return Headliner(
            videoId = video.videoId.ifBlank { progress.videoId },
            episodeLabel = "Continue · Episode ${video.position + 1}",
            title = video.title,
            meta = "with ${video.channelTitle} · ${EditorialDressing.clockLabel(remaining)} remaining",
            positionLabel = EditorialDressing.clockLabel(progress.positionSeconds),
            durationLabel = EditorialDressing.clockLabel(progress.durationSeconds),
            progress = fraction,
        )
    }
}
