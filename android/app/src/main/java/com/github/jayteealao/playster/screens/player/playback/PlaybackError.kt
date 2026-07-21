package com.github.jayteealao.playster.screens.player.playback

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants

/**
 * The editorial playback-failure taxonomy. A pure mapping from the embed's
 * error signal — the library's [PlayerConstants.PlayerError] plus an offline
 * flag the host detects separately — onto the small set of states the Player's
 * error surface distinguishes. Pure and JVM-unit-tested (AC3); no Android or
 * Compose types leak in.
 *
 * Note on the input shape (a deliberate deviation from the plan's "map 101/150
 * vs 153" phrasing): android-youtube-player 13.0.0 does NOT surface the raw
 * IFrame integer codes — it collapses them into the [PlayerConstants.PlayerError]
 * enum before any listener sees them (source:
 * .scratch/sources/ayp/core/.../player/PlayerConstants.kt at tag 13.0.0 —
 * `enum class PlayerError { UNKNOWN, INVALID_PARAMETER_IN_REQUEST, HTML_5_PLAYER,
 * VIDEO_NOT_FOUND, VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER,
 * REQUEST_MISSING_HTTP_REFERER }`). So codes 101/150 arrive already folded into
 * `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER`, 100 into `VIDEO_NOT_FOUND`, and 153
 * into `REQUEST_MISSING_HTTP_REFERER`. The mapper is over the enum, not ints,
 * and a distinct "region-blocked" state is not derivable — YouTube reports a
 * region block as the same embed-disabled/unavailable enum values — so it folds
 * into [EmbedDisabled]/[Unavailable] rather than pretending a distinction the
 * source does not expose.
 */
sealed interface PlaybackError {
    /** The message the editorial error surface renders (specific + actionable). */
    val editorialMessage: String

    /** A short retry-affordance label; null when retrying cannot help. */
    val retryLabel: String?

    /** The owner disabled embedding for this video (IFrame 101/150). */
    data object EmbedDisabled : PlaybackError {
        override val editorialMessage: String =
            "This video's owner doesn't allow it to play outside YouTube. " +
                "The summary, chapters, and notes below are still yours to read."
        override val retryLabel: String? = null
    }

    /** The video is unavailable — removed, private, or region-blocked (IFrame 100). */
    data object Unavailable : PlaybackError {
        override val editorialMessage: String =
            "This video is unavailable — it may have been removed, made private, " +
                "or blocked in your region. The reading below still stands."
        override val retryLabel: String? = null
    }

    /** The referer was missing (IFrame 153) — an integration fault, retry may recover. */
    data object Referer : PlaybackError {
        override val editorialMessage: String =
            "The player couldn't establish a trusted connection to YouTube."
        override val retryLabel: String = "Try the video again"
    }

    /** No network at launch — the embed can't load; the reading is offline-safe. */
    data object Offline : PlaybackError {
        override val editorialMessage: String =
            "You're offline, so the recording can't play. The text below is here to read."
        override val retryLabel: String = "Try again when you're back online"
    }

    /** Any other embed fault (invalid param, HTML5, unknown) — transient by default. */
    data object Unknown : PlaybackError {
        override val editorialMessage: String =
            "Something went wrong loading the recording."
        override val retryLabel: String = "Try the video again"
    }

    companion object {
        /**
         * Map the library error enum to an editorial state. [offline] wins over
         * a concrete embed error because a missing network is the more useful
         * thing to tell the reader (and it is what an airplane-mode launch
         * produces — AC4). Pure; total over the enum.
         */
        fun from(
            error: PlayerConstants.PlayerError,
            offline: Boolean = false,
        ): PlaybackError {
            if (offline) return Offline
            return when (error) {
                PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> EmbedDisabled
                PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> Unavailable
                PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER -> Referer
                PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST,
                PlayerConstants.PlayerError.HTML_5_PLAYER,
                PlayerConstants.PlayerError.UNKNOWN,
                -> Unknown
            }
        }
    }
}
