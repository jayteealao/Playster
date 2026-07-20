package com.github.jayteealao.playster.screens.player.playback

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The player's lifecycle as the reading loop sees it. */
sealed interface PlaybackState {
    /** The embed is initializing — [onReady] not yet fired (the dead-panel window). */
    data object Loading : PlaybackState

    /** Ready and cued at the resume point; awaiting the first play. */
    data object Ready : PlaybackState

    data object Playing : PlaybackState

    data object Paused : PlaybackState

    data object Buffering : PlaybackState

    data object Ended : PlaybackState

    /** A mapped embed failure — the editorial error surface replaces the player area. */
    data class Error(val error: PlaybackError) : PlaybackState
}

/**
 * Controller over one YouTube embed lifecycle. Wraps the library's
 * [YouTubePlayer] (obtained on [YouTubePlayerListener.onReady]) behind the
 * playback verbs the reading loop needs — play / pause / seek / speed — and
 * publishes the position stream (`onCurrentSecond`), the video duration, and a
 * sealed [PlaybackState] as `StateFlow`s the ViewModel folds into `PlayerUiState`.
 *
 * The controller is *remembered* by [YouTubePlayerHost] across recomposition so
 * the WebView never re-inits; the host attaches [listener] to the view exactly
 * once. Resume-from-~T (AC5) is handled by cueing at [startPositionSeconds] on
 * ready — `cueVideo` prepares the frame without autoplaying, which both respects
 * the RMF autoplay rule and gives the reader the editorial "press to listen"
 * beat rather than sound the moment the screen opens.
 */
class PlaybackController(
    private val videoId: String,
    private val startPositionSeconds: Float = 0f,
    private val isOffline: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Loading)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _positionSeconds = MutableStateFlow(startPositionSeconds)
    val positionSeconds: StateFlow<Float> = _positionSeconds.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0f)
    val durationSeconds: StateFlow<Float> = _durationSeconds.asStateFlow()

    private var player: YouTubePlayer? = null
    private var sawError = false

    /**
     * A play/pause request made while [player] was still null (BC-2) — applied
     * once [onReady] assigns the player, so a tap during the Loading window is
     * queued rather than silently dropped. `null` means no pending request.
     */
    private var pendingPlayIntent: Boolean? = null

    /** The single listener [YouTubePlayerHost] attaches to the view. */
    val listener: YouTubePlayerListener =
        object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                // Cue (not load) so playback doesn't start unprompted — RMF
                // autoplay compliance + the editorial "press to listen" beat.
                // Cue from the live position when this is a rebuild (a config
                // change re-initializes the same controller and fires onReady
                // a second time) rather than the constructor-fixed
                // startPositionSeconds, so rotation never silently rewinds
                // progress already made (REL-4).
                val cuePosition = maxOf(startPositionSeconds, _positionSeconds.value).coerceAtLeast(0f)
                youTubePlayer.cueVideo(videoId, cuePosition)
                if (_state.value is PlaybackState.Loading) {
                    _state.value = PlaybackState.Ready
                }
                pendingPlayIntent?.let { wantsPlay ->
                    pendingPlayIntent = null
                    if (wantsPlay) youTubePlayer.play() else youTubePlayer.pause()
                }
            }

            override fun onCurrentSecond(
                youTubePlayer: YouTubePlayer,
                second: Float,
            ) {
                _positionSeconds.value = second
            }

            override fun onVideoDuration(
                youTubePlayer: YouTubePlayer,
                duration: Float,
            ) {
                _durationSeconds.value = duration
            }

            override fun onStateChange(
                youTubePlayer: YouTubePlayer,
                state: PlayerConstants.PlayerState,
            ) {
                // A concrete error state is only cleared by an actual PLAYING —
                // BUFFERING/PAUSED noise must not silently paper over an embed
                // failure surface.
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> {
                        if (sawError) {
                            PlaybackInstrumentation.onPlayerRecovered(videoId)
                            sawError = false
                        }
                        _state.value = PlaybackState.Playing
                    }
                    PlayerConstants.PlayerState.PAUSED ->
                        if (_state.value !is PlaybackState.Error) _state.value = PlaybackState.Paused
                    PlayerConstants.PlayerState.BUFFERING ->
                        if (_state.value !is PlaybackState.Error) _state.value = PlaybackState.Buffering
                    PlayerConstants.PlayerState.ENDED ->
                        if (_state.value !is PlaybackState.Error) _state.value = PlaybackState.Ended
                    PlayerConstants.PlayerState.VIDEO_CUED ->
                        if (_state.value is PlaybackState.Loading) _state.value = PlaybackState.Ready
                    PlayerConstants.PlayerState.UNSTARTED,
                    PlayerConstants.PlayerState.UNKNOWN,
                    -> Unit
                }
            }

            override fun onError(
                youTubePlayer: YouTubePlayer,
                error: PlayerConstants.PlayerError,
            ) {
                sawError = true
                PlaybackInstrumentation.onPlayerError(error, videoId)
                _state.value = PlaybackState.Error(PlaybackError.from(error, isOffline()))
            }
        }

    /**
     * The embed never reached [onReady] within the load window. A network loss
     * during initial load leaves the IFrame unable to fetch its player HTML, and
     * the library fires *neither* `onReady` nor `onError` — so without this the
     * screen hangs forever on the "Cueing the recording…" beat with no editorial
     * error surface (AC4). Surfaces [PlaybackError.Offline] when the network is
     * down, otherwise a retryable [PlaybackError.Unknown]. A no-op (returns null)
     * once the load has already resolved to any non-loading state, so a late
     * watchdog tick can never clobber a live or already-errored player.
     *
     * Pure state mutation (no Android/Firebase calls) so it stays JVM-unit
     * testable; the caller instruments the returned error on-device.
     *
     * @return the error that was surfaced, or null if the load had resolved.
     */
    fun onLoadTimedOut(): PlaybackError? {
        if (_state.value !is PlaybackState.Loading) return null
        sawError = true
        val error = if (isOffline()) PlaybackError.Offline else PlaybackError.Unknown
        _state.value = PlaybackState.Error(error)
        return error
    }

    fun play() {
        val p = player
        if (p == null) {
            pendingPlayIntent = true
            return
        }
        p.play()
    }

    fun pause() {
        val p = player
        if (p == null) {
            pendingPlayIntent = false
            return
        }
        p.pause()
    }

    /** Absolute seek in seconds (used by resume and the chapters jump). */
    fun seekTo(seconds: Float) {
        player?.seekTo(seconds.coerceAtLeast(0f))
    }

    /**
     * Re-target an already-live controller to [seconds] — used by
     * [PlaybackSession.controllerFor] when it reuses this controller for the
     * same video instead of constructing a fresh one, so a `?t=` deep link or
     * a search jump-to-timestamp hit is never silently dropped just because
     * the controller was already live (CR-2). Mirrors the existing cue-vs-seek
     * split: while actively playing, `seekTo` jumps without interrupting
     * playback; otherwise `cueVideo` re-cues paused at the new point, matching
     * a fresh open's "press to listen" beat. A no-op if the player isn't ready
     * yet (rare — the controller is only reused once it's already live).
     */
    fun seekOrCueTo(seconds: Float) {
        val p = player ?: return
        val target = seconds.coerceAtLeast(0f)
        if (_state.value is PlaybackState.Playing) {
            p.seekTo(target)
        } else {
            p.cueVideo(videoId, target)
        }
    }

    /**
     * Re-cue the current video from the last known position — the target of a
     * retryable [PlaybackError]'s "Try the video again" affordance (REL-3),
     * previously inert because no reload path existed and [PlaybackSession]
     * simply returned the cached, still-broken controller. Resets to
     * [PlaybackState.Loading] so the load watchdog re-arms and the existing
     * error mapping applies again if the retry itself fails; a no-op unless
     * the controller is currently in an error state.
     */
    fun retry() {
        if (_state.value !is PlaybackState.Error) return
        sawError = false
        _state.value = PlaybackState.Loading
        player?.cueVideo(videoId, _positionSeconds.value.coerceAtLeast(0f))
    }

    /** Fractional seek [0,1] — the seek bar's drag maps here against the duration. */
    fun seekToFraction(fraction: Float) {
        val duration = _durationSeconds.value
        if (duration > 0f) seekTo(fraction.coerceIn(0f, 1f) * duration)
    }

    /** Set playback speed from a 0.25×–2× float, snapped to the nearest supported rate. */
    fun setSpeed(rate: Float) {
        player?.setPlaybackRate(rate.toPlaybackRate())
    }
}

/** Snap a 0.25×–2× speed float to the nearest [PlayerConstants.PlaybackRate]. */
@Suppress("MagicNumber") // The thresholds ARE the supported YouTube playback-rate values.
internal fun Float.toPlaybackRate(): PlayerConstants.PlaybackRate =
    when {
        this <= 0.25f -> PlayerConstants.PlaybackRate.RATE_0_25
        this <= 0.5f -> PlayerConstants.PlaybackRate.RATE_0_5
        this <= 0.75f -> PlayerConstants.PlaybackRate.RATE_0_75
        this <= 1.0f -> PlayerConstants.PlaybackRate.RATE_1
        this <= 1.25f -> PlayerConstants.PlaybackRate.RATE_1_25
        this <= 1.5f -> PlayerConstants.PlaybackRate.RATE_1_5
        this <= 1.75f -> PlayerConstants.PlaybackRate.RATE_1_75
        else -> PlayerConstants.PlaybackRate.RATE_2
    }
