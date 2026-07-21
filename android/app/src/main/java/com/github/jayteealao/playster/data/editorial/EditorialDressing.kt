package com.github.jayteealao.playster.data.editorial

import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import java.time.Clock
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The editorial dressing — the intellectual core of the Home slice.
 *
 * The mock's front page reads like content, but almost all of it (issue
 * number, weekday, unread-hours aggregate, volume labels, section tags,
 * approximate hours, playlist progress, clock strings) is *derivation*: a set
 * of deterministic, total functions over the real playlist + progress data.
 * Round 5 settled that the backend carries no editorial metadata, so the shelf
 * gets its dressing here, client-side. Every function is pure and side-effect-
 * free — same input, same dressing, always — which is exactly what AC1 asserts
 * and why AC1's complete proof is a JVM unit test.
 *
 * The formulas are *invented* (the mock hardcodes its dressing), so the voice
 * is objectively floored by the pixel gate (AC3, the mock is the C1 contract)
 * and glanced by the PO at the golden-approval sitting. A re-tune is a one-file
 * change with no data migration.
 */
object EditorialDressing {
    /** Fixed masthead furniture — the publication title, not derived from data. */
    const val MASTHEAD_TITLE_TOP: String = "Today's"
    const val MASTHEAD_TITLE_EMPHASIS: String = "shelf."

    /**
     * A Monday epoch and a base issue number. The issue advances one per whole
     * week since the epoch — a stable, deterministic counter, not the mock's
     * literal 38 (a real-data difference enumerated at the pixel gate).
     */
    private val ISSUE_EPOCH: LocalDate = LocalDate.of(2025, 1, 6)
    private const val ISSUE_BASE = 1

    /** Approximate minutes per video — [PlaylistDoc] carries a count, not a total duration. */
    private const val AVG_MINUTES_PER_VIDEO = 21

    /** Progress at or below this fraction is hidden (the mock's `> 0.02` rule). */
    private const val HIDE_THRESHOLD = 0.02f

    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    /** Whole weeks since the epoch, plus the base — the masthead issue number. */
    fun issueNumber(clock: Clock): Int {
        val today = LocalDate.now(clock)
        val weeks = java.time.temporal.ChronoUnit.WEEKS.between(ISSUE_EPOCH, today)
        return ISSUE_BASE + weeks.toInt().coerceAtLeast(0)
    }

    /** Today's weekday, full English name — the masthead dateline. */
    fun dateLabel(clock: Clock): String = LocalDate.now(clock).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /**
     * The right-hand masthead kicker: whole hours of unwatched material summed
     * across the shelf. Each playlist contributes `(1 − watched) × estMinutes`,
     * where `estMinutes = videoCount × AVG_MINUTES_PER_VIDEO`.
     */
    fun unreadHoursLabel(
        playlists: List<PlaylistDoc>,
        videoProgressByPlaylist: Map<String, List<ProgressDoc>>,
    ): String {
        val unwatchedMinutes =
            playlists.sumOf { playlist ->
                val watched = watchedFraction(videoProgressByPlaylist[playlist.id].orEmpty())
                val estMinutes = playlist.videoCount * AVG_MINUTES_PER_VIDEO
                ((1f - watched) * estMinutes).toDouble()
            }
        val hours = (unwatchedMinutes / MINUTES_PER_HOUR).roundToInt()
        return "${hours}h unread"
    }

    /** The masthead deck — started vs. never-opened, in the mock's cadence. */
    fun mastheadDeck(
        startedCount: Int,
        unopenedCount: Int,
    ): String = "$startedCount playlists you started, $unopenedCount you haven't. A few minutes each."

    /**
     * "Vol. 01" — the playlist's 1-based index in a *stable* ordering
     * (`stableOrder`, sorted by `publishedAt` then id by the caller), so a
     * volume number doesn't renumber when the shelf reorders by last-opened.
     */
    fun volumeLabel(
        playlist: PlaylistDoc,
        stableOrder: List<PlaylistDoc>,
    ): String {
        val index = stableOrder.indexOfFirst { it.id == playlist.id }
        val ordinal = if (index >= 0) index + 1 else 1
        return "Vol. %02d".format(ordinal)
    }

    /** The section tag — a curated keyword map over title + channel, neutral fallback. */
    fun tagLabel(playlist: PlaylistDoc): String {
        val haystack = "${playlist.title} ${playlist.channelTitle}".lowercase(Locale.ENGLISH)
        return when {
            haystack.contains("type") -> "Typography"
            haystack.contains("motion") || haystack.contains("animat") -> "Motion"
            haystack.contains("frontend") || haystack.contains("architect") || haystack.contains("code") -> "Code"
            haystack.contains("product") -> "Product"
            haystack.contains("design") || haystack.contains("interface") -> "Design"
            haystack.contains("inbox") || haystack.contains("saved") || haystack.contains("later") -> "Inbox"
            else -> "Reading"
        }
    }

    /** Approximate total hours from the video count (no per-video-duration read). */
    fun hoursLabel(playlist: PlaylistDoc): String {
        val totalMinutes = (playlist.videoCount * AVG_MINUTES_PER_VIDEO).toInt()
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return if (hours > 0) "${hours}h %02dm".format(minutes) else "${minutes}m"
    }

    /** Raw watched fraction over a playlist's video-progress docs (0 when none). */
    fun watchedFraction(videoProgress: List<ProgressDoc>): Float {
        val totalDuration = videoProgress.sumOf { it.durationSeconds }
        if (totalDuration <= 0L) return 0f
        val totalPosition = videoProgress.sumOf { it.positionSeconds }
        return (totalPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    }

    /** Shelf-row progress: the watched fraction, or null at or below the hide threshold. */
    fun shelfProgress(videoProgress: List<ProgressDoc>): Float? {
        val fraction = watchedFraction(videoProgress)
        return if (fraction > HIDE_THRESHOLD) fraction else null
    }

    /** "12:47" — minutes:seconds for positions, durations, and remaining. */
    fun clockLabel(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val minutes = safe / SECONDS_PER_MINUTE
        val seconds = safe % SECONDS_PER_MINUTE
        return "%d:%02d".format(minutes, seconds)
    }
}
