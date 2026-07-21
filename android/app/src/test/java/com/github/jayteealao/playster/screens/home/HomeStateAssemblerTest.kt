package com.github.jayteealao.playster.screens.home

import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.VideoDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The pure Home-state assembly (the `HomeViewModel`-shaped assertions that bite
 * without a device): empty→Empty, shelf orders by the query's last-opened head
 * then the never-opened tail, headliner selection + join, progress hiding.
 *
 * The `HomeViewModel` flow glue (loading initial, error catch, retry) is not a
 * JVM unit here — faking the concrete Firestore-backed repositories would need
 * either an unrequested interface or a mocking lib absent from the classpath;
 * the loading/error render is proven by the Roborazzi goldens and AC2 drives
 * the live query path.
 */
class HomeStateAssemblerTest {
    private val clock: Clock =
        Clock.fixed(LocalDate.of(2026, 7, 14).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun playlist(
        id: String,
        title: String = "Title $id",
        channel: String = "Channel",
        videoCount: Long = 10L,
        publishedAt: String,
    ) = PlaylistDoc(
        id = id,
        title = title,
        channelTitle = channel,
        videoCount = videoCount,
        publishedAt = publishedAt,
    )

    private fun playlistProgress(playlistId: String) = ProgressDoc(kind = "playlist", playlistId = playlistId)

    private fun videoProgress(
        videoId: String,
        playlistId: String,
        position: Long,
        duration: Long,
    ) = ProgressDoc(
        kind = "video",
        videoId = videoId,
        playlistId = playlistId,
        positionSeconds = position,
        durationSeconds = duration,
    )

    @Test
    fun allEmpty_isEmptyState() {
        val state = HomeState.assemble(emptyList(), emptyList(), emptyList(), null, clock)
        assertEquals(HomeUiState.Empty, state)
    }

    @Test
    fun shelf_ordersByLastOpenedHeadThenNeverOpenedTail() {
        val p1 = playlist("ed-p1", publishedAt = "2025-11-01T00:00:00Z")
        val p2 = playlist("ed-p2", publishedAt = "2025-11-02T00:00:00Z")
        val p5 = playlist("ed-p5", publishedAt = "2025-11-03T00:00:00Z")
        // Query hands the assembler shelf progress already ordered last-opened desc.
        val shelfProgress = listOf(playlistProgress("ed-p2"), playlistProgress("ed-p1"))

        val state =
            HomeState.assemble(
                playlists = listOf(p1, p2, p5),
                shelfProgress = shelfProgress,
                videoProgress = emptyList(),
                continueHeadliner = null,
                clock = clock,
            )

        assertTrue(state is HomeUiState.Content)
        val shelf = (state as HomeUiState.Content).shelf
        assertEquals(listOf("ed-p2", "ed-p1", "ed-p5"), shelf.map { it.playlistId })
        assertEquals(listOf(1, 2, 3), shelf.map { it.ordinal })
    }

    @Test
    fun shelf_progressHiddenWhenNoneOrBelowThreshold() {
        val p1 = playlist("ed-p1", publishedAt = "2025-11-01T00:00:00Z")
        val state =
            HomeState.assemble(
                playlists = listOf(p1),
                shelfProgress = listOf(playlistProgress("ed-p1")),
                videoProgress = listOf(videoProgress("v", "ed-p1", 767, 1394)),
                continueHeadliner = null,
                clock = clock,
            )
        val row = (state as HomeUiState.Content).shelf.single()
        assertEquals(0.5502f, row.progress!!, 0.001f)
    }

    @Test
    fun headliner_joinsVideoDocAndComputesRemaining() {
        val p1 = playlist("ed-p1", publishedAt = "2025-11-01T00:00:00Z")
        val progress = videoProgress("ed-v09", "ed-p1", 767, 1394)
        val video =
            VideoDoc(
                videoId = "ed-v09",
                title = "The Future of Design Systems",
                channelTitle = "Joey Banks",
                position = 8,
            )
        val state =
            HomeState.assemble(
                playlists = listOf(p1),
                shelfProgress = listOf(playlistProgress("ed-p1")),
                videoProgress = listOf(progress),
                continueHeadliner = progress to video,
                clock = clock,
            )
        val headliner = (state as HomeUiState.Content).headliner!!
        assertEquals("ed-v09", headliner.videoId)
        assertEquals("Continue · Episode 9", headliner.episodeLabel)
        assertEquals("The Future of Design Systems", headliner.title)
        assertEquals("with Joey Banks · 10:27 remaining", headliner.meta)
        assertEquals("12:47", headliner.positionLabel)
        assertEquals("23:14", headliner.durationLabel)
        assertEquals(0.5502f, headliner.progress, 0.001f)
    }

    @Test
    fun headliner_absentWhenNoVideoProgress() {
        val p1 = playlist("ed-p1", publishedAt = "2025-11-01T00:00:00Z")
        val state =
            HomeState.assemble(
                playlists = listOf(p1),
                shelfProgress = listOf(playlistProgress("ed-p1")),
                videoProgress = emptyList(),
                continueHeadliner = null,
                clock = clock,
            )
        assertNull((state as HomeUiState.Content).headliner)
    }

    @Test
    fun content_playlistsWithoutProgress_renderAsNeverOpened() {
        val p1 = playlist("ed-p1", publishedAt = "2025-11-01T00:00:00Z")
        // Playlists exist but no progress at all → Content with all-never-opened shelf, not Empty.
        val state = HomeState.assemble(listOf(p1), emptyList(), emptyList(), null, clock)
        assertTrue(state is HomeUiState.Content)
        val row = (state as HomeUiState.Content).shelf.single()
        assertEquals("ed-p1", row.playlistId)
        assertNull(row.progress)
    }
}
