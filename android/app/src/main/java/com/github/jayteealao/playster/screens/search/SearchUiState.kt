package com.github.jayteealao.playster.screens.search

import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.VideoWithContext
import com.github.jayteealao.playster.functions.TranscriptSearchHit

/**
 * The Search screen's UI model — deliberately JVM-pure (no Compose or Firestore
 * types) so [SearchStateAssembler]'s title-match, grouping, and filtered-empty
 * logic is unit-testable device-free (AC1/AC3), and the sealed transcript-group
 * states render under Robolectric goldens.
 *
 * The hybrid split is visible in the shape: [videos]/[playlists] are the instant,
 * network-free title matches; [transcript] is the debounced backend leg carrying
 * its OWN sub-state, so a backend failure degrades that group alone and never
 * masquerades as "no transcript matches" (R2).
 */
data class SearchUiState(
    val query: String,
    val transcript: TranscriptSearchState,
    val videos: List<VideoResult>,
    val playlists: List<PlaylistResult>,
    val recents: List<String>,
) {
    val hasQuery: Boolean get() = query.isNotBlank()

    /** Instant title matches across both groups — the network-free result count. */
    val titleResultCount: Int get() = videos.size + playlists.size

    companion object {
        val Initial =
            SearchUiState(
                query = "",
                transcript = TranscriptSearchState.Idle,
                videos = emptyList(),
                playlists = emptyList(),
                recents = emptyList(),
            )
    }
}

/** One instant video title match. [meta] is the italic byline line. */
data class VideoResult(
    val videoId: String,
    val title: String,
    val meta: String,
)

/** One instant playlist title match. [meta] is the "{n} videos · {dek}" line. */
data class PlaylistResult(
    val playlistId: String,
    val title: String,
    val meta: String,
)

/** One backend transcript hit, dressed with a snippet and a jump-to-timestamp. */
data class TranscriptResult(
    val videoId: String,
    val title: String,
    val snippet: String,
    val jumpLabel: String,
    val startSeconds: Double,
)

/**
 * The backend transcript-search leg's own sealed sub-state — the split that keeps
 * a failed callable legible as an error *for the transcript group only*.
 */
sealed interface TranscriptSearchState {
    /** No query yet, or below the 2-char minimum the callable accepts. */
    data object Idle : TranscriptSearchState

    /** The debounced call is in flight (the instant title half stays rendered). */
    data object Loading : TranscriptSearchState

    /** The callable returned hits. */
    data class Results(val items: List<TranscriptResult>) : TranscriptSearchState

    /** The callable returned no matching transcript segments. */
    data object Empty : TranscriptSearchState

    /** The callable failed — the editorial error surface for this group. */
    data class Error(val message: String) : TranscriptSearchState
}

/**
 * The VM's intermediate for the backend leg before the video corpus is joined in
 * — raw hits so [SearchStateAssembler] does the title join purely.
 */
sealed interface TranscriptLeg {
    data object Idle : TranscriptLeg

    data object Loading : TranscriptLeg

    data class Hits(val hits: List<TranscriptSearchHit>) : TranscriptLeg

    data class Error(val message: String) : TranscriptLeg
}

/** Alias to keep the assembler signatures readable. */
internal typealias PlaylistCorpus = List<PlaylistDoc>
internal typealias VideoCorpus = List<VideoWithContext>
