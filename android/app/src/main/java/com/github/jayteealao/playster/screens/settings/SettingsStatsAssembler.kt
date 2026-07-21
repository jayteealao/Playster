package com.github.jayteealao.playster.screens.settings

import com.github.jayteealao.playster.data.firestore.HighlightDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.google.firebase.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The reading-stats derivation — pure over the progress/highlight docs the
 * reading loop already writes, plus the persisted default-speed preference and an
 * injected [Clock] for deterministic day/week bucketing. Total and side-effect
 * free, so every formula is proven by an exact-output JVM unit test (AC3); no
 * analytics library, no event-tracking infra (the Round-5 restraint).
 *
 * The choice envelope ("compute from progress docs, no event infra") is
 * PO-ratified; only the arithmetic below is autonomous, and it is documented
 * precisely because two of the four stats are proxies for signals the app never
 * records:
 *  - **streak** — the run of consecutive local calendar days, ending at the most
 *    recent active day, on which at least one video-progress doc was updated;
 *    counted as the *current* streak only if that run reaches today or yesterday
 *    (one grace day), else 0.
 *  - **hoursThisWeek** — Σ `positionSeconds` (the resume/furthest position, the
 *    only listened-time signal that exists — a documented proxy, not literal watch
 *    time) over video-progress docs updated within the last 7 local days, ÷ 3600,
 *    rounded to one decimal.
 *  - **avgSpeed** — the persisted default-speed preference (no per-play speed
 *    history is recorded, so the default IS the only speed signal; this matches
 *    the mock, where avg-speed and default-speed read identically).
 *  - **highlightsThisWeek** — count of highlight docs created within the last 7
 *    local days.
 */
object SettingsStatsAssembler {
    /** The reading-stats quartet, ready for the masthead grid + deck. */
    data class Stats(
        val streak: Int,
        val hoursThisWeek: Double,
        val avgSpeed: Float,
        val highlightsThisWeek: Int,
    )

    /** The 7-day window is [today − 6 days, today] inclusive — 7 local calendar days. */
    private const val WEEK_WINDOW_DAYS = 6L
    private const val SECONDS_PER_HOUR = 3600.0
    private const val ONE_DECIMAL = 10.0

    fun assemble(
        videoProgress: List<ProgressDoc>,
        highlights: List<HighlightDoc>,
        defaultSpeed: Float,
        clock: Clock,
    ): Stats =
        Stats(
            streak = streak(videoProgress, clock),
            hoursThisWeek = hoursThisWeek(videoProgress, clock),
            avgSpeed = defaultSpeed,
            highlightsThisWeek = highlightsThisWeek(highlights, clock),
        )

    fun streak(
        videoProgress: List<ProgressDoc>,
        clock: Clock,
    ): Int {
        val today = LocalDate.now(clock)
        val activeDays = videoProgress.mapNotNull { it.updatedAt.toLocalDate(clock) }.toSortedSet()
        val mostRecent = activeDays.lastOrNull()
        // A streak is only "current" if the run reaches today or yesterday.
        if (mostRecent == null || mostRecent.isBefore(today.minusDays(1))) return 0

        var count = 0
        var cursor: LocalDate = mostRecent
        while (cursor in activeDays) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun hoursThisWeek(
        videoProgress: List<ProgressDoc>,
        clock: Clock,
    ): Double {
        val today = LocalDate.now(clock)
        val totalSeconds =
            videoProgress
                .filter { it.updatedAt.isWithinWeek(today, clock) }
                .sumOf { it.positionSeconds.coerceAtLeast(0L) }
        return (totalSeconds / SECONDS_PER_HOUR * ONE_DECIMAL).roundToLong() / ONE_DECIMAL
    }

    fun highlightsThisWeek(
        highlights: List<HighlightDoc>,
        clock: Clock,
    ): Int {
        val today = LocalDate.now(clock)
        return highlights.count { it.createdAt.isWithinWeek(today, clock) }
    }

    /** The masthead deck sentence, exactly as the mock phrases it. */
    fun shelfDeck(
        playlistCount: Int,
        transcriptCount: Int,
        highlightsThisWeek: Int,
    ): String =
        "$playlistCount playlists on your shelf. " +
            "$transcriptCount transcripts saved. " +
            "$highlightsThisWeek highlights this week."

    /** "12" — the streak count as its grid numeral. */
    fun streakLabel(streak: Int): String = streak.toString()

    /** "4.2h" — hours-this-week to one decimal with the h suffix. */
    fun hoursLabel(hoursThisWeek: Double): String = String.format(Locale.US, "%.1fh", hoursThisWeek)

    /** "1.25×" — the default/avg speed to two decimals with the multiplier glyph. */
    fun speedLabel(speed: Float): String = String.format(Locale.US, "%.2f×", speed)

    private fun Timestamp?.toLocalDate(clock: Clock): LocalDate? {
        val ts = this ?: return null
        return Instant.ofEpochSecond(ts.seconds, ts.nanoseconds.toLong()).atZone(clock.zone).toLocalDate()
    }

    private fun Timestamp?.isWithinWeek(
        today: LocalDate,
        clock: Clock,
    ): Boolean {
        val date = toLocalDate(clock) ?: return false
        return !date.isBefore(today.minusDays(WEEK_WINDOW_DAYS)) && !date.isAfter(today)
    }
}
