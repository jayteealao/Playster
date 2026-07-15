package com.github.jayteealao.playster.screens.playlist

import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The Playlist (volume) screen's sealed state. Deliberately JVM-pure — no
 * Compose or Firestore types leak in — so the whole assembly is device-free
 * unit-testable ([PlaylistState.assemble]) and the tabs' sealed/edge states
 * render under Robolectric goldens.
 *
 * The Summary tab wraps the *existing* [SummaryUiState] plus a quota flag,
 * introducing no new summary/quota vocabulary: the product semantics the
 * regression flows guard stay in the shared model.
 */
sealed interface PlaylistUiState {
    /** First snapshot pending. */
    data object Loading : PlaylistUiState

    /** A flow terminated with an error (e.g. a Firestore listener failure). */
    data object Error : PlaylistUiState

    /** The assembled volume view. */
    data class Content(
        val cover: Cover,
        val episodes: List<EpisodeEntry>,
        val summary: SummaryTabState,
        val notes: List<NoteEntry>,
        val folioRight: String,
    ) : PlaylistUiState
}

/** The volume cover block: accent kicker, title, dek, byline, continue action. */
data class Cover(
    val appBarKicker: String,
    val kicker: String,
    val title: String,
    val dek: String,
    val byline: String,
    val continueLabel: String,
    val continueVideoId: String?,
    val folioLeft: String,
)

/** One episode row: ordinal, title, mm:ss duration, watched/playing markers. */
data class EpisodeEntry(
    val position: Int,
    val videoId: String,
    val title: String,
    val durationLabel: String,
    val watched: Boolean,
    val playing: Boolean,
)

/** One note row: the accent "AT mm:ss" timestamp and the serif note text. */
data class NoteEntry(
    val timestampLabel: String,
    val text: String,
)

/**
 * The Summary tab's state: the existing per-video [SummaryUiState] plus whether
 * the daily/per-minute quota is exhausted (the re-skinned banner appears iff
 * true) and, when a completed summary exists, the derived "what to read first"
 * pointer. No new sealed vocabulary — the summary/quota semantics are the shared
 * model's.
 */
data class SummaryTabState(
    val summary: SummaryUiState,
    val quotaExhausted: Boolean,
    val readFirst: String?,
)

/**
 * Pure assembly of the Playlist state from the data sources, routed through
 * [EditorialDressing]. Total and side-effect-free: this is where AC1's
 * per-playlist episode assembly, the watched/playing join, the summary/quota
 * wrapping, and the notes ordering live — the [PlaylistViewModel]-shaped
 * assertions that bite without a device.
 */
object PlaylistState {
    /** A video is "watched" once its resume position reaches this fraction. */
    private const val WATCHED_FRACTION = 0.95f

    /**
     * @param playlist the cover's playlist doc (null → the id resolved no doc yet: Loading upstream)
     * @param allPlaylists every playlist, for the stable volume numbering
     * @param videos the playlist's own episodes, already position-ordered
     * @param videoProgress every video-kind progress doc (filtered here to this playlist)
     * @param summary the observed summary doc state for the representative episode
     * @param quotaExhausted whether the quota banner should appear
     * @param notes this playlist's notes, already newest-first
     * @param lastOpenedMillis the playlist progress doc's lastOpenedAt (epoch ms), or null
     */
    @Suppress("LongParameterList") // Screen glue combining the volume's five real sources; each is load-bearing.
    fun assemble(
        playlist: PlaylistDoc,
        allPlaylists: List<PlaylistDoc>,
        videos: List<VideoDoc>,
        videoProgress: List<ProgressDoc>,
        summary: SummaryUiState,
        quotaExhausted: Boolean,
        notes: List<NoteDoc>,
        lastOpenedMillis: Long?,
        clock: Clock,
    ): PlaylistUiState.Content {
        val progressMap = progressByVideo(playlist, videos, videoProgress)
        val summaryVideoId = selectSummaryVideoId(videos, progressMap)

        val episodes =
            videos.mapIndexed { index, video ->
                val progress = progressMap[video.videoId]
                EpisodeEntry(
                    position = index + 1,
                    videoId = video.videoId,
                    title = video.title,
                    durationLabel = durationLabel(video),
                    watched = progress != null && watchedFraction(progress) >= WATCHED_FRACTION,
                    playing = video.videoId == summaryVideoId,
                )
            }

        val stableOrder = allPlaylists.sortedWith(compareBy({ it.publishedAt }, { it.id }))
        val volume = EditorialDressing.volumeLabel(playlist, stableOrder)
        val summaryEpisode = episodes.firstOrNull { it.videoId == summaryVideoId }
        val continueLabel =
            summaryEpisode?.let { "Continue · Ep ${it.position}" } ?: "Start reading"
        val readFirst =
            episodes.firstOrNull()?.let { "Begin with Episode ${it.position} — “${it.title}”." }
        val folioRight =
            if (summaryEpisode != null) {
                "${summaryEpisode.position} / ${playlist.videoCount}"
            } else {
                "${videos.size} / ${playlist.videoCount}"
            }

        return PlaylistUiState.Content(
            cover =
                Cover(
                    appBarKicker = "$volume · ${EditorialDressing.tagLabel(playlist)}",
                    kicker = "A series in ${playlist.videoCount} videos",
                    title = playlist.title,
                    dek = dek(playlist),
                    byline = byline(playlist, lastOpenedMillis, clock),
                    continueLabel = continueLabel,
                    continueVideoId = summaryVideoId ?: episodes.firstOrNull()?.videoId,
                    folioLeft = "$volume · ${playlist.title}",
                ),
            episodes = episodes,
            summary =
                SummaryTabState(
                    summary = summary,
                    quotaExhausted = quotaExhausted,
                    readFirst = if (summary is SummaryUiState.Completed) readFirst else null,
                ),
            notes =
                notes.map { note ->
                    NoteEntry(
                        timestampLabel = "AT ${EditorialDressing.clockLabel(note.t.toLong())}",
                        text = note.text,
                    )
                },
            folioRight = folioRight,
        )
    }

    /**
     * The representative episode the Summary tab binds to (Assumption A1),
     * exposed so the [PlaylistViewModel] observes the same `summaries/{videoId}`
     * doc the assembler marks "playing" — one source of truth for the binding.
     */
    fun summaryTargetId(
        playlist: PlaylistDoc,
        videos: List<VideoDoc>,
        videoProgress: List<ProgressDoc>,
    ): String? = selectSummaryVideoId(videos, progressByVideo(playlist, videos, videoProgress))

    private fun progressByVideo(
        playlist: PlaylistDoc,
        videos: List<VideoDoc>,
        videoProgress: List<ProgressDoc>,
    ): Map<String, ProgressDoc> {
        val episodeIds = videos.map { it.videoId }.toSet()
        return videoProgress
            .filter { it.playlistId == playlist.id || it.videoId in episodeIds }
            .associateBy { it.videoId }
    }

    /**
     * The representative episode the Summary tab binds to (Assumption A1): the
     * most-recently-touched episode in this playlist, first-episode fallback,
     * null only for an episode-less playlist.
     */
    private fun selectSummaryVideoId(
        videos: List<VideoDoc>,
        progressByVideo: Map<String, ProgressDoc>,
    ): String? {
        if (videos.isEmpty()) return null
        val mostRecent =
            videos
                .mapNotNull { video -> progressByVideo[video.videoId] }
                .maxByOrNull { it.updatedAt?.seconds ?: Long.MIN_VALUE }
        return mostRecent?.videoId ?: videos.first().videoId
    }

    /**
     * The volume dek — the playlist's own description when it carries one, or a
     * derived one-liner over the section tag and video count when the backend
     * left it blank. Reuses [EditorialDressing.tagLabel]; total and pure.
     */
    private fun dek(playlist: PlaylistDoc): String {
        val described = playlist.description.trim()
        if (described.isNotEmpty()) return described
        val tag = EditorialDressing.tagLabel(playlist).lowercase(java.util.Locale.ENGLISH)
        return "A $tag volume in ${playlist.videoCount} videos."
    }

    private fun byline(
        playlist: PlaylistDoc,
        lastOpenedMillis: Long?,
        clock: Clock,
    ): String {
        val base = "by ${playlist.channelTitle} · ${EditorialDressing.hoursLabel(playlist)}"
        val opened = lastOpenedLabel(lastOpenedMillis, clock)
        return if (opened != null) "$base · last opened $opened" else base
    }

    /**
     * "today" / "yesterday" / "N days ago" for the cover's "last opened" byline,
     * from an epoch-millis instant against the supplied clock. Null when the
     * playlist was never opened, so the caller drops the clause entirely as the
     * mock does for a never-opened volume.
     */
    private fun lastOpenedLabel(
        lastOpenedMillis: Long?,
        clock: Clock,
    ): String? {
        if (lastOpenedMillis == null) return null
        val opened = Instant.ofEpochMilli(lastOpenedMillis).atZone(clock.zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(opened, LocalDate.now(clock)).coerceAtLeast(0)
        return when (days) {
            0L -> "today"
            1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    private fun watchedFraction(progress: ProgressDoc): Float {
        if (progress.durationSeconds <= 0L) return 0f
        return (progress.positionSeconds.toFloat() / progress.durationSeconds.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * mm:ss from [VideoDoc.duration]'s ISO-8601 (`PT23M14S`) via `java.time`
     * (available on minSdk 29 without desugaring), reusing [EditorialDressing.clockLabel].
     * A malformed/blank duration renders as a dash rather than crashing the row.
     */
    private fun durationLabel(video: VideoDoc): String {
        val iso = video.duration.trim()
        if (iso.isEmpty()) return "—"
        return try {
            EditorialDressing.clockLabel(Duration.parse(iso).seconds)
        } catch (_: java.time.format.DateTimeParseException) {
            "—"
        }
    }
}
