package com.github.jayteealao.playster.screens.search

import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.data.firestore.VideoWithContext
import com.github.jayteealao.playster.functions.TranscriptSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC1 (instant title match — pure, network-free), AC3 (filtered-empty names the
 * query), and AC5's degradation logic (a backend failure maps to the transcript
 * group's error while the title results survive). The live no-round-trip drive,
 * the jump-to-T landing, and the fault-injection event fire ride the shared
 * emulator probe; the filter/grouping/mapping/empty logic is proven here.
 */
class SearchStateAssemblerTest {
    private fun playlist(
        id: String,
        title: String,
        channel: String = "Andy Allen",
        count: Long = 12,
        description: String = "",
    ) = PlaylistDoc(id = id, title = title, channelTitle = channel, videoCount = count, description = description)

    private fun video(
        videoId: String,
        title: String,
        channel: String = "Andy Allen",
        playlistId: String = "p1",
        duration: String = "18:33",
    ) = VideoWithContext(
        video = VideoDoc(id = videoId, videoId = videoId, title = title, channelTitle = channel, duration = duration),
        playlistId = playlistId,
    )

    private val playlists =
        listOf(
            playlist("p1", "Designing Better Interfaces", description = "Where type meets the screen."),
            playlist("p2", "Modern Type Practice", channel = "Ellen Lupton"),
        )
    private val videos =
        listOf(
            video("v1", "Tokens, but actually useful", playlistId = "p1"),
            video("v2", "A11y is the system", playlistId = "p1"),
            video("v3", "The drop cap returns", channel = "Ellen Lupton", playlistId = "p2"),
        )

    @Test
    fun blankQuery_matchesNothing() {
        val matches = SearchStateAssembler.matchTitles("   ", playlists, videos)
        assertTrue(matches.videos.isEmpty())
        assertTrue(matches.playlists.isEmpty())
    }

    @Test
    fun titleFragment_matchesCaseInsensitively_bothGroups() {
        val matches = SearchStateAssembler.matchTitles("type", playlists, videos)
        // Playlist "Modern Type Practice" matches on title.
        assertEquals(listOf("Modern Type Practice"), matches.playlists.map { it.title })
        // No video title contains "type".
        assertTrue(matches.videos.isEmpty())
    }

    @Test
    fun channelFragment_matchesVideosByChannel() {
        val matches = SearchStateAssembler.matchTitles("andy allen", playlists, videos)
        assertEquals(setOf("v1", "v2"), matches.videos.map { it.videoId }.toSet())
    }

    @Test
    fun videoMeta_joinsChannelDurationAndPlaylistTitle() {
        val matches = SearchStateAssembler.matchTitles("tokens", playlists, videos)
        val v1 = matches.videos.single()
        assertEquals("by Andy Allen · 18:33 · in Designing Better Interfaces", v1.meta)
    }

    @Test
    fun transcriptRows_joinTitleByVideoId_andFormatJump() {
        val hits =
            listOf(
                TranscriptSearchHit(videoId = "v1", start = 78.0, snippet = "…paint bucket…", score = 12.0),
                TranscriptSearchHit(videoId = "unknown", start = 5.0, snippet = "orphan hit", score = 3.0),
            )
        val rows = SearchStateAssembler.transcriptRows(hits, videos)
        assertEquals("Tokens, but actually useful", rows[0].title)
        assertEquals("JUMP TO 1:18", rows[0].jumpLabel)
        assertEquals(78.0, rows[0].startSeconds, 0.0)
        // An orphan hit (no corpus video) still renders as a bare snippet result.
        assertEquals("In this transcript", rows[1].title)
    }

    @Test
    fun filteredEmpty_true_whenQueryPresentAndNothingMatches() {
        val state =
            SearchUiState.Initial.copy(
                query = "zzzznomatch",
                transcript = TranscriptSearchState.Empty,
            )
        assertTrue(SearchStateAssembler.isFilteredEmpty(state))
    }

    @Test
    fun filteredEmpty_false_whileLoading_orOnError_orWithResults() {
        val loading = SearchUiState.Initial.copy(query = "x", transcript = TranscriptSearchState.Loading)
        val error = SearchUiState.Initial.copy(query = "x", transcript = TranscriptSearchState.Error("down"))
        val withTitle =
            SearchUiState.Initial.copy(
                query = "x",
                transcript = TranscriptSearchState.Idle,
                videos = listOf(VideoResult("v1", "t", "m")),
            )
        assertFalse(SearchStateAssembler.isFilteredEmpty(loading))
        assertFalse(SearchStateAssembler.isFilteredEmpty(error))
        assertFalse(SearchStateAssembler.isFilteredEmpty(withTitle))
    }

    @Test
    fun filteredEmpty_false_whenNoQuery() {
        assertFalse(SearchStateAssembler.isFilteredEmpty(SearchUiState.Initial))
    }

    @Test
    fun resolveTranscript_backendError_degradesGroupOnly() {
        // AC5 proxy: the Error leg becomes the transcript group's error sub-state;
        // the caller composes it alongside the (independent) title results.
        val state = SearchStateAssembler.resolveTranscript(TranscriptLeg.Error("down"), videos)
        assertTrue(state is TranscriptSearchState.Error)
    }

    @Test
    fun resolveTranscript_emptyHits_isEmpty_notBlank() {
        val state = SearchStateAssembler.resolveTranscript(TranscriptLeg.Hits(emptyList()), videos)
        assertEquals(TranscriptSearchState.Empty, state)
    }

    @Test
    fun resolveTranscript_hits_becomeResults() {
        val leg = TranscriptLeg.Hits(listOf(TranscriptSearchHit("v1", 78.0, "snip", 9.0)))
        val state = SearchStateAssembler.resolveTranscript(leg, videos)
        assertTrue(state is TranscriptSearchState.Results)
        assertEquals(1, (state as TranscriptSearchState.Results).items.size)
    }
}
