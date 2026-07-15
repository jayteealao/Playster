package com.github.jayteealao.playster.screens.player

import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.SummaryChapter
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure Player assembly (header/chapters/notes/resume) and the progress-write
 * throttle cadence — the ViewModel-shaped logic that bites without a device:
 * AC5's "never per-second" throttle and resume, AC6's NOW-index (covered in the
 * resolver test), and the header/chapter/note dressing.
 *
 * The `PlayerViewModel` flow glue (combine, flatMapLatest onto notes, the async
 * description fetch, error catch) is not a JVM unit here — faking the concrete
 * Firestore-backed repositories needs an unrequested interface or a mocking lib
 * absent from the classpath (the same wall Home/Playlist hit); the goldens prove
 * the render and the live drive proves the query path.
 */
class PlayerStateAssemblerTest {
    private val video =
        VideoDoc(
            videoId = "ed-v09",
            title = "The Future of Design Systems",
            channelTitle = "Joey Banks",
            duration = "PT23M14S",
            publishedAt = "2025-11-02T09:00:00Z",
            viewCount = 128_400,
            position = 2,
        )
    private val playlist =
        PlaylistDoc(id = "ed-p1", title = "Designing Better Interfaces", channelTitle = "Joey Banks", videoCount = 14)

    @Test
    fun header_readsEpisodeWayfindingAndMeta() {
        val content =
            PlayerStateAssembler.assemble(
                video = video,
                playlist = playlist,
                progress = null,
                chapters = emptyList(),
                notes = emptyList(),
                summary = SummaryUiState.NoSummary,
            )
        assertEquals("Ep 3 / 14 · Design", content.header.kicker)
        assertEquals("The Future of Design Systems", content.header.title)
        assertEquals("by Joey Banks", content.header.byline)
        assertTrue(content.header.meta.contains("Nov 2, 2025"))
        assertTrue(content.header.meta.contains("128,400 views"))
        assertEquals("3 / 14", content.folioRight)
        assertEquals("ed-p1", content.playlistId)
    }

    @Test
    fun noPlaylistContext_stillAssembles() {
        val content =
            PlayerStateAssembler.assemble(
                video = video,
                playlist = null,
                progress = null,
                chapters = emptyList(),
                notes = emptyList(),
                summary = SummaryUiState.NoSummary,
            )
        assertTrue(content.header.kicker.endsWith("Now playing"))
        assertEquals("Ep 3", content.folioRight)
    }

    @Test
    fun chaptersAndNotes_dress() {
        val content =
            PlayerStateAssembler.assemble(
                video = video,
                playlist = playlist,
                progress = null,
                chapters =
                    listOf(
                        SummaryChapter(t = 0.0, label = "Intro", dur = 222.0),
                        SummaryChapter(t = 222.0, label = "The point", dur = null),
                    ),
                notes =
                    listOf(
                        NoteDoc(
                            videoId = "ed-v09",
                            playlistId = "ed-p1",
                            t = 767.0,
                            text = "The 5-line contract idea.",
                        ),
                    ),
                summary = SummaryUiState.NoSummary,
            )
        assertEquals("0:00", content.chapters[0].timeLabel)
        assertEquals("3:42", content.chapters[0].durationLabel)
        assertEquals("", content.chapters[1].durationLabel)
        assertEquals("AT 12:47", content.notes[0].timestampLabel)
    }

    @Test
    fun resume_readsStoredPositionUnlessNearEnd() {
        val mid = progress(position = 767, duration = 1394)
        val content =
            PlayerStateAssembler.assemble(video, playlist, mid, emptyList(), emptyList(), SummaryUiState.NoSummary)
        assertEquals(767f, content.resumeSeconds, 0f)
        assertTrue(PlayerStateAssembler.shouldResume(mid))
        assertFalse(PlayerStateAssembler.shouldResume(null))
        assertFalse(PlayerStateAssembler.shouldResume(progress(position = 0, duration = 1394)))
        // Watched to the end → don't resume mid-credits.
        assertFalse(PlayerStateAssembler.shouldResume(progress(position = 1390, duration = 1394)))
    }

    @Test
    fun throttle_neverWritesPerSecond_periodicEvery15s_andOnPause() {
        val throttle = ProgressWriteThrottle(periodMillis = 15_000L)
        // First tick establishes the baseline — no write.
        assertFalse(throttle.onTick(1_000L, isPlaying = true))
        // Per-second ticks in the window: none write.
        var writes = 0
        for (t in 2_000L until 16_000L step 1_000L) {
            if (throttle.onTick(t, isPlaying = true)) writes++
        }
        assertEquals(0, writes)
        // At the 15s boundary a periodic write is due.
        assertTrue(throttle.onTick(16_000L, isPlaying = true))
        // Immediately after, no write.
        assertFalse(throttle.onTick(17_000L, isPlaying = true))
        // A play→pause transition writes immediately.
        assertTrue(throttle.onTick(18_000L, isPlaying = false))
    }

    private fun progress(
        position: Long,
        duration: Long,
    ) = ProgressDoc(
        kind = "video",
        videoId = "ed-v09",
        playlistId = "ed-p1",
        positionSeconds = position,
        durationSeconds = duration,
    )
}
