package com.github.jayteealao.playster.screens.search

import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.VideoWithContext
import com.github.jayteealao.playster.functions.TranscriptSearchHit

/**
 * The pure core of Search: instant case-insensitive title matching, result
 * grouping, transcript-hit → row mapping, and the filtered-empty test that names
 * the query. Total and side-effect-free — this is where AC1's no-network filter
 * logic and AC3's never-blank empty detection live and where the unit tests bite
 * without a device or an emulator.
 */
object SearchStateAssembler {
    /**
     * Assemble the instant title half: playlists and videos whose title (or
     * channel) contains the trimmed query, case-insensitively. A blank query
     * matches nothing (the screen shows recents, not the whole corpus). The
     * grouping order — transcript, then video, then playlist — is set by the
     * screen; here each group is filtered and dressed independently.
     */
    fun matchTitles(
        query: String,
        playlists: PlaylistCorpus,
        videos: VideoCorpus,
    ): TitleMatches {
        val needle = query.trim()
        if (needle.isEmpty()) return TitleMatches(emptyList(), emptyList())

        val playlistTitleById = playlists.associate { it.id to it.title }

        val videoResults =
            videos
                .filter { it.video.title.matches(needle) || it.video.channelTitle.matches(needle) }
                .map { entry ->
                    VideoResult(
                        videoId = entry.video.videoId,
                        title = entry.video.title,
                        meta = videoMeta(entry, playlistTitleById[entry.playlistId]),
                    )
                }

        val playlistResults =
            playlists
                .filter { it.title.matches(needle) || it.channelTitle.matches(needle) }
                .map { playlist ->
                    PlaylistResult(
                        playlistId = playlist.id,
                        title = playlist.title,
                        meta = playlistMeta(playlist),
                    )
                }

        return TitleMatches(videos = videoResults, playlists = playlistResults)
    }

    /**
     * Join backend transcript hits to the in-memory video corpus by videoId for
     * a display title; a hit with no corpus video still renders as a bare snippet
     * result (the snippet + jump-to-T is the load-bearing content, not the title).
     */
    fun transcriptRows(
        hits: List<TranscriptSearchHit>,
        videos: VideoCorpus,
    ): List<TranscriptResult> {
        val titleById = videos.associate { it.video.videoId to it.video.title }
        return hits.map { hit ->
            TranscriptResult(
                videoId = hit.videoId,
                title = titleById[hit.videoId]?.takeIf { it.isNotBlank() } ?: "In this transcript",
                snippet = hit.snippet,
                jumpLabel = "JUMP TO ${EditorialDressing.clockLabel(hit.start.toLong())}",
                startSeconds = hit.start,
            )
        }
    }

    /**
     * Resolve the backend leg into the transcript group's display sub-state,
     * joining raw hits to the video corpus for titles. A failure maps to
     * [TranscriptSearchState.Error] (AC5's degradation: the transcript group
     * alone shows the error; the caller keeps the instant title results); an
     * empty hit list maps to [TranscriptSearchState.Empty], never a blank.
     */
    fun resolveTranscript(
        leg: TranscriptLeg,
        videos: VideoCorpus,
    ): TranscriptSearchState =
        when (leg) {
            TranscriptLeg.Idle -> TranscriptSearchState.Idle
            TranscriptLeg.Loading -> TranscriptSearchState.Loading
            is TranscriptLeg.Error -> TranscriptSearchState.Error(leg.message)
            is TranscriptLeg.Hits -> {
                val rows = transcriptRows(leg.hits, videos)
                if (rows.isEmpty()) TranscriptSearchState.Empty else TranscriptSearchState.Results(rows)
            }
        }

    /**
     * Filtered-empty (AC3): the reader typed a query, but neither the instant
     * title half nor the settled backend leg produced anything. Deliberately
     * false while the backend leg is still Loading (never flash an empty state
     * mid-search) and false on a backend Error (that group shows its own error).
     */
    fun isFilteredEmpty(state: SearchUiState): Boolean {
        val noTitleMatches = state.hasQuery && state.titleResultCount == 0
        return noTitleMatches &&
            when (state.transcript) {
                TranscriptSearchState.Idle, TranscriptSearchState.Empty -> true
                is TranscriptSearchState.Results -> state.transcript.items.isEmpty()
                TranscriptSearchState.Loading, is TranscriptSearchState.Error -> false
            }
    }

    private fun String.matches(needle: String): Boolean = contains(needle, ignoreCase = true)

    private fun videoMeta(
        entry: VideoWithContext,
        playlistTitle: String?,
    ): String =
        listOfNotNull(
            entry.video.channelTitle.takeIf { it.isNotBlank() }?.let { "by $it" },
            entry.video.duration.takeIf { it.isNotBlank() },
            playlistTitle?.takeIf { it.isNotBlank() }?.let { "in $it" },
        ).joinToString(" · ")

    private fun playlistMeta(playlist: PlaylistDoc): String =
        listOfNotNull(
            "${playlist.videoCount} videos",
            playlist.description.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

/** The two instant title groups. */
data class TitleMatches(
    val videos: List<VideoResult>,
    val playlists: List<PlaylistResult>,
)
