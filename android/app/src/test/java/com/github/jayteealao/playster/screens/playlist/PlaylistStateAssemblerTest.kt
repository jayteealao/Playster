package com.github.jayteealao.playster.screens.playlist

import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.QuotaDoc
import com.github.jayteealao.playster.data.firestore.SummaryDoc
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.screens.common.state.QuotaState
import com.github.jayteealao.playster.screens.common.state.toQuotaState
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import com.github.jayteealao.playster.screens.videoDetail.summary.mapSummaryDocToState
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The pure Playlist-state assembly (the `PlaylistViewModel`-shaped assertions
 * that bite without a device): AC1's per-playlist disjoint episodes with real
 * durations + watched/playing join, AC2/AC5's summary sealed-state + quota
 * mapping (semantics preserved via the shared mapper), notes newest-first, and
 * the 0-video / all-watched edge states.
 *
 * The `PlaylistViewModel` flow glue (combine, flatMapLatest onto the summary,
 * error catch, retry) is not a JVM unit here — faking the concrete
 * Firestore-backed repositories would need either an unrequested interface or a
 * mocking lib absent from the classpath (the same wall the Home slice hit); the
 * loading/error render is proven by the Roborazzi goldens and AC1/AC3 drive the
 * live query path on the emulator.
 */
class PlaylistStateAssemblerTest {
    private val clock: Clock =
        Clock.fixed(LocalDate.of(2026, 7, 14).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun playlist(
        id: String,
        title: String = "Title $id",
        channel: String = "Channel",
        videoCount: Long = 6L,
        publishedAt: String = "2025-11-02T09:00:00Z",
    ) = PlaylistDoc(
        id = id,
        title = title,
        channelTitle = channel,
        description = "A season worth a second listen.",
        videoCount = videoCount,
        publishedAt = publishedAt,
    )

    private fun video(
        id: String,
        title: String,
        position: Int,
        duration: String,
    ) = VideoDoc(videoId = id, title = title, position = position.toLong(), duration = duration)

    private fun videoProgress(
        videoId: String,
        playlistId: String,
        position: Long,
        duration: Long,
        updatedSeconds: Long,
    ) = ProgressDoc(
        kind = "video",
        videoId = videoId,
        playlistId = playlistId,
        positionSeconds = position,
        durationSeconds = duration,
        updatedAt = Timestamp(updatedSeconds, 0),
    )

    private val p1 =
        playlist("ed-p1", title = "Designing Better Interfaces", channel = "Joey Banks", videoCount = 3)
    private val p2 =
        playlist("ed-p2", title = "Modern Type Practice", channel = "Ellen Lupton", videoCount = 2)

    private val p1Videos =
        listOf(
            video("ed-v01", "Why systems plateau", 0, "PT12M8S"),
            video("ed-v02", "Tokens, actually", 1, "PT18M33S"),
            video("ed-v09", "The Future", 2, "PT23M14S"),
        )
    private val p2Videos =
        listOf(
            video("ed-v13", "Anatomy of a typeface", 0, "PT9M42S"),
            video("ed-v15", "Variable fonts", 1, "PT22M4S"),
        )

    private fun assemble(
        playlist: PlaylistDoc,
        videos: List<VideoDoc>,
        videoProgress: List<ProgressDoc> = emptyList(),
        summary: SummaryUiState = SummaryUiState.NoSummary,
        notes: List<NoteDoc> = emptyList(),
    ) = PlaylistState.assemble(
        playlist = playlist,
        allPlaylists = listOf(p1, p2),
        videos = videos,
        videoProgress = videoProgress,
        summary = summary,
        quotaExhausted = false,
        notes = notes,
        lastOpenedMillis = null,
        clock = clock,
    )

    @Test
    fun eachPlaylist_showsOnlyItsOwnEpisodes_withRealDurations() {
        val s1 = assemble(p1, p1Videos)
        val s2 = assemble(p2, p2Videos)

        assertEquals(listOf("ed-v01", "ed-v02", "ed-v09"), s1.episodes.map { it.videoId })
        assertEquals(listOf("ed-v13", "ed-v15"), s2.episodes.map { it.videoId })
        // Disjoint: no id from p2 appears in p1's assembly and vice-versa.
        assertTrue(s1.episodes.map { it.videoId }.none { it in s2.episodes.map { e -> e.videoId } })
        // Real ISO-8601 durations become mm:ss.
        assertEquals("23:14", s1.episodes.last().durationLabel)
        assertEquals("9:42", s2.episodes.first().durationLabel)
        // 1-based ordinals in position order.
        assertEquals(listOf(1, 2, 3), s1.episodes.map { it.position })
    }

    @Test
    fun watchedAndPlaying_reflectSeededProgress() {
        val progress =
            listOf(
                // ed-v01 ~99% → watched.
                videoProgress("ed-v01", "ed-p1", 720, 728, updatedSeconds = 100),
                // ed-v09 mid, most-recently touched → the "playing"/representative.
                videoProgress("ed-v09", "ed-p1", 767, 1394, updatedSeconds = 500),
            )
        val state = assemble(p1, p1Videos, progress)
        val byId = state.episodes.associateBy { it.videoId }
        assertTrue(byId.getValue("ed-v01").watched)
        assertFalse(byId.getValue("ed-v01").playing)
        assertTrue(byId.getValue("ed-v09").playing)
        assertFalse(byId.getValue("ed-v09").watched)
        // The continue action targets the representative episode.
        assertEquals("ed-v09", state.cover.continueVideoId)
        assertEquals("Continue · Ep 3", state.cover.continueLabel)
    }

    @Test
    fun summaryTab_preservesSealedSemantics_andQuotaFlag() {
        // The shared wire→state mapper drives the exact five-state table.
        assertTrue(mapSummaryDocToState(summaryDoc("completed")) is SummaryUiState.Completed)
        assertTrue(mapSummaryDocToState(summaryDoc("pending")) is SummaryUiState.InProgress)
        assertTrue(mapSummaryDocToState(summaryDoc("running")) is SummaryUiState.InProgress)
        assertTrue(mapSummaryDocToState(summaryDoc("failed-transient")) is SummaryUiState.FailedTransient)
        assertTrue(mapSummaryDocToState(summaryDoc("failed-permanent")) is SummaryUiState.FailedPermanent)
        assertTrue(mapSummaryDocToState(null) is SummaryUiState.NoSummary)

        val completed = SummaryUiState.Completed(content = "Body.", model = "free")
        val state =
            PlaylistState.assemble(
                playlist = p1,
                allPlaylists = listOf(p1, p2),
                videos = p1Videos,
                videoProgress = emptyList(),
                summary = completed,
                quotaExhausted = true,
                notes = emptyList(),
                lastOpenedMillis = null,
                clock = clock,
            )
        assertEquals(completed, state.summary.summary)
        assertTrue(state.summary.quotaExhausted)
        // "What to read first" pointer only appears with a completed summary.
        assertTrue(state.summary.readFirst!!.contains("Begin with Episode 1"))
        assertNull(assemble(p1, p1Videos, summary = SummaryUiState.InProgress).summary.readFirst)
    }

    @Test
    fun quotaDoc_exhausted_mapsToDisabled_semanticsPreserved() {
        // The exhausted quota doc still maps through the shared model unchanged.
        val exhausted = QuotaDoc(requestCount = 1000, dailyLimit = 1000)
        assertEquals(QuotaState.DailyExhausted, exhausted.toQuotaState())
        assertTrue(exhausted.toQuotaState().isDisabled)
        assertEquals(QuotaState.Healthy, QuotaDoc().toQuotaState())
    }

    @Test
    fun notes_renderNewestFirst_withTimestampLabels() {
        val notes =
            listOf(
                NoteDoc(videoId = "ed-v09", playlistId = "ed-p1", t = 767.0, text = "The 5-line contract idea."),
                NoteDoc(videoId = "ed-v09", playlistId = "ed-p1", t = 222.0, text = "Ask for the source."),
            )
        val state = assemble(p1, p1Videos, notes = notes)
        assertEquals(2, state.notes.size)
        assertEquals("AT 12:47", state.notes[0].timestampLabel)
        assertEquals("The 5-line contract idea.", state.notes[0].text)
        assertEquals("AT 3:42", state.notes[1].timestampLabel)
    }

    @Test
    fun zeroVideoPlaylist_hasNoEpisodesNoContinueTarget() {
        val state = assemble(p1.copy(videoCount = 0), emptyList())
        assertTrue(state.episodes.isEmpty())
        assertNull(state.cover.continueVideoId)
        assertEquals("Start reading", state.cover.continueLabel)
        assertEquals("0 / 0", state.folioRight)
    }

    @Test
    fun allWatchedPlaylist_marksEveryEpisodeWatched() {
        val progress =
            p1Videos.mapIndexed { i, v ->
                videoProgress(v.videoId, "ed-p1", 1000, 1000, updatedSeconds = i.toLong())
            }
        val state = assemble(p1, p1Videos, progress)
        assertTrue(state.episodes.all { it.watched })
    }

    private fun summaryDoc(status: String): SummaryDoc {
        return SummaryDoc(videoId = "ed-v09", statusWire = status, content = "Body.", model = "free")
    }
}
