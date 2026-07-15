package com.github.jayteealao.playster.screens.player

import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.SummaryChapter
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure assembly of the Player state from the data sources plus the pure
 * throttle that governs progress writes — the [PlayerViewModel]-shaped logic
 * that bites without a device (AC5 cadence, AC6 NOW-index, resume). Total and
 * side-effect-free; reuses [EditorialDressing] for the same clock/tag dressing
 * the rest of the app speaks.
 */
object PlayerStateAssembler {
    private const val WATCHED_FRACTION = 0.95f
    private val PUBLISHED_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    /**
     * @param video the resolved video document (header source)
     * @param playlist the playlist context, or null when the route carried none
     * @param progress this video's progress doc (resume position), or null
     * @param chapters the resolved chapters (precedence already applied), possibly empty
     * @param notes this video's notes, newest-first
     * @param summary the observed summary state for the Summary tab
     */
    @Suppress("LongParameterList") // Screen glue over the player's real sources; each is load-bearing.
    fun assemble(
        video: VideoDoc,
        playlist: PlaylistDoc?,
        progress: ProgressDoc?,
        chapters: List<SummaryChapter>,
        notes: List<NoteDoc>,
        summary: SummaryUiState,
    ): PlayerUiState.Content {
        val episode = (video.position + 1).toInt()
        val total = playlist?.videoCount?.toInt() ?: 0
        val tag = playlist?.let { EditorialDressing.tagLabel(it) } ?: "Reading"

        val kicker =
            if (total > 0) "Ep $episode / $total · $tag" else "$tag · Now playing"
        val folioRight = if (total > 0) "$episode / $total" else "Ep $episode"
        val folioLeft = playlist?.let { "$tag · ${it.title}" } ?: video.channelTitle

        return PlayerUiState.Content(
            videoId = video.videoId,
            playlistId = playlist?.id ?: progress?.playlistId ?: "",
            header =
                PlayerHeader(
                    kicker = kicker,
                    title = video.title,
                    byline = "by ${video.channelTitle}",
                    meta = metaLine(video),
                ),
            summary = summary,
            chapters = chapters.map(::chapterEntry),
            notes =
                notes.map { note ->
                    PlayerNote(
                        timestampLabel = "AT ${EditorialDressing.clockLabel(note.t.toLong())}",
                        text = note.text,
                    )
                },
            resumeSeconds = progress?.positionSeconds?.toFloat()?.coerceAtLeast(0f) ?: 0f,
            folioLeft = folioLeft,
            folioRight = folioRight,
        )
    }

    /** Whether a resume seek should fire — a stored position that isn't the very start or the end. */
    fun shouldResume(progress: ProgressDoc?): Boolean {
        if (progress == null || progress.positionSeconds <= 0L) return false
        val duration = progress.durationSeconds
        return duration <= 0L ||
            progress.positionSeconds.toFloat() / duration.toFloat() < WATCHED_FRACTION
    }

    private fun chapterEntry(chapter: SummaryChapter): ChapterEntry =
        ChapterEntry(
            timeLabel = EditorialDressing.clockLabel(chapter.t.toLong()),
            label = chapter.label,
            durationLabel = chapter.dur?.let { EditorialDressing.clockLabel(it.toLong()) }.orEmpty(),
            startSeconds = chapter.t.toFloat(),
        )

    private fun metaLine(video: VideoDoc): String {
        val published = publishedLabel(video.publishedAt)
        val views = viewsLabel(video.viewCount)
        return listOf(published, views).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun publishedLabel(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            Instant.parse(iso).atZone(ZoneId.of("UTC")).toLocalDate().format(PUBLISHED_FORMAT)
        } catch (_: java.time.format.DateTimeParseException) {
            ""
        }
    }

    private fun viewsLabel(viewCount: Long): String {
        if (viewCount <= 0L) return ""
        return "%,d views".format(Locale.ENGLISH, viewCount)
    }
}

/**
 * The progress-write throttle (AC5): never per-second. A write is due on a
 * play→pause transition (the reader stopped — capture the position now) or
 * periodically while playing (every [periodMillis], 15s by default). Exit
 * writes are the caller's (forced on `onStop`) — [markWritten] lets the caller
 * fold a forced write into the cadence so a periodic write doesn't immediately
 * follow. Stateful but clock-free (the caller supplies `nowMillis`), so the
 * cadence is deterministically unit-testable.
 */
class ProgressWriteThrottle(
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
) {
    private var lastWriteMillis: Long = Long.MIN_VALUE
    private var lastWasPlaying: Boolean = false

    /** Returns true when this tick should trigger a progress write. */
    fun onTick(
        nowMillis: Long,
        isPlaying: Boolean,
    ): Boolean {
        val pausedTransition = lastWasPlaying && !isPlaying
        lastWasPlaying = isPlaying
        if (lastWriteMillis == Long.MIN_VALUE) {
            // First observed tick establishes the cadence baseline (position is
            // ~0 here) — write only if the very first thing is a pause.
            lastWriteMillis = nowMillis
            return pausedTransition
        }
        val periodic = isPlaying && nowMillis - lastWriteMillis >= periodMillis
        val due = pausedTransition || periodic
        if (due) lastWriteMillis = nowMillis
        return due
    }

    /** Record that a write happened at [nowMillis] (e.g. a forced exit write). */
    fun markWritten(nowMillis: Long) {
        lastWriteMillis = nowMillis
    }

    private companion object {
        const val DEFAULT_PERIOD_MILLIS = 15_000L
    }
}
