package com.github.jayteealao.playster.screens.settings

import com.github.jayteealao.playster.data.firestore.HighlightDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

/**
 * AC3 — the reading stats equal the documented formulas exactly. A fixed clock
 * pins "today" so streak / week-window / highlight arithmetic is deterministic;
 * avg-speed is the passed default-speed, and the deck string is byte-checked.
 */
class SettingsStatsAssemblerTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 7, 15)
    private val clock: Clock = Clock.fixed(today.atTime(12, 0).toInstant(zone), zone)

    private fun onDay(
        date: LocalDate,
        positionSeconds: Long = 0L,
    ) = ProgressDoc(
        kind = "video",
        videoId = "v-$date",
        positionSeconds = positionSeconds,
        updatedAt = date.timestamp(),
    )

    private fun highlightOn(date: LocalDate) = HighlightDoc(videoId = "v", createdAt = date.timestamp())

    private fun LocalDate.timestamp(): Timestamp = Timestamp(Date.from(atStartOfDay(zone).toInstant()))

    // ── streak ──────────────────────────────────────────────────────────────

    @Test
    fun streak_countsConsecutiveRunEndingToday() {
        val progress = listOf(onDay(today), onDay(today.minusDays(1)), onDay(today.minusDays(2)))
        assertEquals(3, SettingsStatsAssembler.streak(progress, clock))
    }

    @Test
    fun streak_breaksAtAGap() {
        val progress =
            listOf(onDay(today), onDay(today.minusDays(1)), onDay(today.minusDays(3)), onDay(today.minusDays(4)))
        assertEquals(2, SettingsStatsAssembler.streak(progress, clock))
    }

    @Test
    fun streak_yesterdayGraceCounts() {
        val progress = listOf(onDay(today.minusDays(1)), onDay(today.minusDays(2)))
        assertEquals(2, SettingsStatsAssembler.streak(progress, clock))
    }

    @Test
    fun streak_staleRunIsZero() {
        val progress = listOf(onDay(today.minusDays(2)), onDay(today.minusDays(3)))
        assertEquals(0, SettingsStatsAssembler.streak(progress, clock))
    }

    @Test
    fun streak_emptyIsZero() {
        assertEquals(0, SettingsStatsAssembler.streak(emptyList(), clock))
    }

    @Test
    fun streak_duplicateSameDayCountsOnce() {
        val progress = listOf(onDay(today), onDay(today), onDay(today.minusDays(1)))
        assertEquals(2, SettingsStatsAssembler.streak(progress, clock))
    }

    // ── hours this week ─────────────────────────────────────────────────────

    @Test
    fun hoursThisWeek_sumsPositionsInWindowOnly() {
        val progress =
            listOf(
                onDay(today, positionSeconds = 3600),
                onDay(today.minusDays(3), positionSeconds = 1800),
                // Outside the 7-day window (today - 7): excluded.
                onDay(today.minusDays(7), positionSeconds = 9999),
            )
        assertEquals(1.5, SettingsStatsAssembler.hoursThisWeek(progress, clock), 0.0001)
    }

    @Test
    fun hoursThisWeek_windowBoundaryIsInclusive() {
        val progress = listOf(onDay(today.minusDays(6), positionSeconds = 3600))
        assertEquals(1.0, SettingsStatsAssembler.hoursThisWeek(progress, clock), 0.0001)
    }

    @Test
    fun hoursThisWeek_roundsToOneDecimal() {
        // 15120s = 4.2h exactly; 100s extra rounds to 4.2h still.
        val progress = listOf(onDay(today, positionSeconds = 15_120 + 100))
        assertEquals(4.2, SettingsStatsAssembler.hoursThisWeek(progress, clock), 0.0001)
    }

    // ── highlights this week ────────────────────────────────────────────────

    @Test
    fun highlightsThisWeek_countsWithinWindow() {
        val highlights =
            listOf(
                highlightOn(today),
                highlightOn(today.minusDays(6)),
                highlightOn(today.minusDays(7)),
            )
        assertEquals(2, SettingsStatsAssembler.highlightsThisWeek(highlights, clock))
    }

    // ── avg speed + labels + deck ───────────────────────────────────────────

    @Test
    fun avgSpeed_isTheDefaultSpeed() {
        val stats = SettingsStatsAssembler.assemble(emptyList(), emptyList(), defaultSpeed = 1.25f, clock = clock)
        assertEquals(1.25f, stats.avgSpeed)
    }

    @Test
    fun labels_matchDocumentedFormatting() {
        assertEquals("12", SettingsStatsAssembler.streakLabel(12))
        assertEquals("4.2h", SettingsStatsAssembler.hoursLabel(4.2))
        assertEquals("1.25×", SettingsStatsAssembler.speedLabel(1.25f))
        assertEquals("1.00×", SettingsStatsAssembler.speedLabel(1.0f))
    }

    @Test
    fun shelfDeck_matchesTheMockPhrasing() {
        assertEquals(
            "23 playlists on your shelf. 184 transcripts saved. 12 highlights this week.",
            SettingsStatsAssembler.shelfDeck(playlistCount = 23, transcriptCount = 184, highlightsThisWeek = 12),
        )
    }
}
