package com.github.jayteealao.playster.data.editorial

import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * AC1: the editorial dressing is deterministic and matches the documented
 * formulas — same input, same dressing, always. Pure JVM; no device, no
 * emulator. Masthead determinism rides an injected fixed [Clock].
 */
class EditorialDressingTest {
    private fun clockOn(date: LocalDate): Clock {
        val instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        return Clock.fixed(instant, ZoneOffset.UTC)
    }

    private fun playlist(
        id: String,
        title: String = "",
        channel: String = "",
        videoCount: Long = 0L,
        publishedAt: String = "2025-11-02T09:00:00Z",
    ) = PlaylistDoc(
        id = id,
        title = title,
        channelTitle = channel,
        videoCount = videoCount,
        publishedAt = publishedAt,
    )

    private fun videoProgress(
        playlistId: String,
        position: Long,
        duration: Long,
    ) = ProgressDoc(
        kind = "video",
        playlistId = playlistId,
        positionSeconds = position,
        durationSeconds = duration,
    )

    // --- issue number + date (masthead) ---

    @Test
    fun issueNumber_atEpoch_isBase() {
        // 2025-01-06 is the epoch Monday: zero whole weeks elapsed → base issue 1.
        assertEquals(1, EditorialDressing.issueNumber(clockOn(LocalDate.of(2025, 1, 6))))
    }

    @Test
    fun issueNumber_threeWeeksLater_advancesByThree() {
        // Epoch + 21 days = three whole weeks → base + 3.
        assertEquals(4, EditorialDressing.issueNumber(clockOn(LocalDate.of(2025, 1, 27))))
    }

    @Test
    fun dateLabel_isFullWeekday() {
        assertEquals("Tuesday", EditorialDressing.dateLabel(clockOn(LocalDate.of(2026, 7, 14))))
    }

    @Test
    fun issueNumber_isDeterministic() {
        val clock = clockOn(LocalDate.of(2025, 6, 1))
        assertEquals(EditorialDressing.issueNumber(clock), EditorialDressing.issueNumber(clock))
    }

    // --- clock labels ---

    @Test
    fun clockLabel_formatsMinutesSeconds() {
        assertEquals("12:47", EditorialDressing.clockLabel(767))
        assertEquals("23:14", EditorialDressing.clockLabel(1394))
        assertEquals("10:27", EditorialDressing.clockLabel(627))
        assertEquals("0:00", EditorialDressing.clockLabel(0))
    }

    @Test
    fun clockLabel_clampsNegative() {
        assertEquals("0:00", EditorialDressing.clockLabel(-30))
    }

    // --- hours (approximate) ---

    @Test
    fun hoursLabel_approximatesFromCount() {
        // 14 videos × 21 min = 294 min = 4h 54m.
        assertEquals("4h 54m", EditorialDressing.hoursLabel(playlist("p", videoCount = 14)))
    }

    @Test
    fun hoursLabel_underOneHour_dropsHours() {
        // 2 videos × 21 = 42 min.
        assertEquals("42m", EditorialDressing.hoursLabel(playlist("p", videoCount = 2)))
    }

    // --- volume (stable ordering) ---

    @Test
    fun volumeLabel_usesStableOrder() {
        val a = playlist("a", publishedAt = "2025-01-01T00:00:00Z")
        val b = playlist("b", publishedAt = "2025-02-01T00:00:00Z")
        // Stable order: publishedAt asc — a before b regardless of input order.
        val order = listOf(b, a).sortedWith(compareBy({ it.publishedAt }, { it.id }))
        assertEquals("Vol. 01", EditorialDressing.volumeLabel(a, order))
        assertEquals("Vol. 02", EditorialDressing.volumeLabel(b, order))
    }

    // --- tag (curated map) ---

    @Test
    fun tagLabel_curatedMap() {
        assertEquals("Design", EditorialDressing.tagLabel(playlist("p", "Designing Better Interfaces", "Joey Banks")))
        assertEquals("Typography", EditorialDressing.tagLabel(playlist("p", "Modern Type Practice", "Ellen Lupton")))
        assertEquals("Motion", EditorialDressing.tagLabel(playlist("p", "Motion, Honestly", "Val Head")))
        assertEquals("Product", EditorialDressing.tagLabel(playlist("p", "Product Notes", "Lenny")))
        assertEquals("Code", EditorialDressing.tagLabel(playlist("p", "Frontend Architecture", "Theo")))
        assertEquals("Inbox", EditorialDressing.tagLabel(playlist("p", "Inbox", "Saved for later")))
        assertEquals("Reading", EditorialDressing.tagLabel(playlist("p", "Something Else", "Anon")))
    }

    // --- progress (hide at ≤2%) ---

    @Test
    fun watchedFraction_sumsPositionsOverDurations() {
        val docs = listOf(videoProgress("p", 767, 1394), videoProgress("p", 1210, 1269))
        val expected = (767f + 1210f) / (1394f + 1269f)
        assertEquals(expected, EditorialDressing.watchedFraction(docs), 0.0001f)
    }

    @Test
    fun watchedFraction_emptyIsZero() {
        assertEquals(0f, EditorialDressing.watchedFraction(emptyList()), 0f)
    }

    @Test
    fun shelfProgress_hidesAtOrBelowTwoPercent() {
        assertNull(EditorialDressing.shelfProgress(listOf(videoProgress("p", 10, 1000))))
        assertNull(EditorialDressing.shelfProgress(emptyList()))
    }

    @Test
    fun shelfProgress_showsAboveTwoPercent() {
        val progress = EditorialDressing.shelfProgress(listOf(videoProgress("p", 767, 1394)))
        assertEquals(0.5502f, progress!!, 0.001f)
    }

    // --- unread hours (aggregate) ---

    @Test
    fun unreadHoursLabel_sumsUnwatchedEstimate() {
        val pl = playlist("p", videoCount = 14)
        val byPlaylist = mapOf("p" to listOf(videoProgress("p", 767, 1394)))
        // watched≈0.5502, est=14×21=294, unwatched≈132.2 min ≈ 2h.
        assertEquals("2h unread", EditorialDressing.unreadHoursLabel(listOf(pl), byPlaylist))
    }

    @Test
    fun unreadHoursLabel_isDeterministic() {
        val pl = playlist("p", videoCount = 9)
        val byPlaylist = mapOf("p" to listOf(videoProgress("p", 100, 900)))
        assertEquals(
            EditorialDressing.unreadHoursLabel(listOf(pl), byPlaylist),
            EditorialDressing.unreadHoursLabel(listOf(pl), byPlaylist),
        )
    }
}
