package com.github.jayteealao.playster.screens.player.playback

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * The one live YouTube embed, hoisted out of any single screen's composition so
 * it survives Player→Transcript navigation (the PO's ratified Option-1 — a
 * persistent *visible* mini-embed on the Transcript, RIM-7). Before this, the
 * [PlaybackController] + [YouTubePlayerView] were `remember`ed inside
 * `PlayerScreen` and released on nav-away, so a standalone `transcript/{videoId}`
 * route could not inherit the still-live embed. This holder is
 * `@ActivityRetainedScoped`, so both the Player and Transcript routes render the
 * *same* controller + retained view; the transcript never instantiates a second
 * player. Client-side only — no backend surface (C6 fence intact).
 *
 * Lifecycle discipline (C5 / YouTube RMF — nothing plays hidden):
 * - The retained view is **re-parented** across routes, never re-inited on a
 *   mere route change ([view] detaches it from its old Compose container before
 *   the new host attaches it). It is released only on a video change or on
 *   [release] (activity finish) — the AC5 resume-write the player owed on
 *   dispose still fires from the player's own effect.
 * - Playback surfaces attach/detach around composition ([attach] / [detach]).
 *   When the last playback surface leaves and none re-attaches within a short
 *   window — i.e. the reader navigated to Home/Playlist/Search, not Player↔
 *   Transcript — playback is **paused** (not released) so nothing plays off a
 *   visible surface, and returning resumes instantly. The debounce is what keeps
 *   the Player→Transcript handoff continuous across the one-frame gap.
 * - The view also observes the hosting activity's lifecycle, so backgrounding
 *   the app pauses the embed (the library's `ON_STOP` pause) and a config-change
 *   recreation rebuilds the view against the new activity.
 */
@ActivityRetainedScoped
class PlaybackSession
    @Inject
    constructor() {
        private val mainHandler = Handler(Looper.getMainLooper())

        private var currentVideoId: String? = null
        private var controller: PlaybackController? = null
        private var playerView: YouTubePlayerView? = null
        private var hostedLifecycle: Lifecycle? = null
        private var activeSurfaces = 0

        private val pauseIfIdle =
            Runnable {
                if (activeSurfaces == 0) controller?.pause()
            }

        /**
         * The single controller for [videoId], created on first request and
         * reused across routes. A different [videoId] tears down the prior embed
         * and starts fresh (so switching episodes never leaves a ghost player).
         */
        fun controllerFor(
            videoId: String,
            startPositionSeconds: Float,
            isOffline: () -> Boolean,
        ): PlaybackController {
            val existing = controller
            if (existing != null && currentVideoId == videoId) return existing
            releaseView()
            return PlaybackController(
                videoId = videoId,
                startPositionSeconds = startPositionSeconds,
                isOffline = isOffline,
            ).also {
                controller = it
                currentVideoId = videoId
            }
        }

        /**
         * The retained embed view for the current controller, ready to be placed
         * into the calling host's `AndroidView`. Created once and re-parented on
         * subsequent calls; rebuilt if the hosting activity was recreated. The
         * IFrame option literals below ARE the option codes (no web controls /
         * related videos / fullscreen), hence the `MagicNumber` suppression.
         */
        @Suppress("MagicNumber")
        fun view(host: Context): YouTubePlayerView {
            val lifecycle = host.findComponentActivity()?.lifecycle
            var view = playerView
            if (view == null || (lifecycle != null && lifecycle !== hostedLifecycle)) {
                releaseView()
                view =
                    YouTubePlayerView(host).apply {
                        enableAutomaticInitialization = false
                    }
                val options =
                    IFramePlayerOptions.Builder(host)
                        .controls(0)
                        .rel(0)
                        .ivLoadPolicy(3)
                        .fullscreen(0)
                        .build()
                controller?.let { view.initialize(it.listener, handleNetworkEvents = true, playerOptions = options) }
                lifecycle?.addObserver(view)
                playerView = view
                hostedLifecycle = lifecycle
            }
            // Detach from the previous Compose container so the new host can re-parent it.
            (view.parent as? ViewGroup)?.removeView(view)
            return view
        }

        /** A playback surface (Player or Transcript embed) entered composition. */
        fun attach() {
            activeSurfaces++
            mainHandler.removeCallbacks(pauseIfIdle)
        }

        /**
         * A playback surface left composition. If none re-attaches within
         * [IDLE_PAUSE_MS] — the reader went to a non-playback route, not the
         * Player↔Transcript handoff — playback pauses so nothing plays off a
         * visible surface (C5). The embed stays retained for instant resume.
         */
        fun detach() {
            if (activeSurfaces > 0) activeSurfaces--
            mainHandler.removeCallbacks(pauseIfIdle)
            mainHandler.postDelayed(pauseIfIdle, IDLE_PAUSE_MS)
        }

        /** Full teardown (activity finish): release the embed and drop the controller. */
        fun release() {
            mainHandler.removeCallbacks(pauseIfIdle)
            releaseView()
            controller = null
            currentVideoId = null
            activeSurfaces = 0
        }

        private fun releaseView() {
            val view = playerView ?: return
            hostedLifecycle?.removeObserver(view)
            (view.parent as? ViewGroup)?.removeView(view)
            view.release()
            playerView = null
            hostedLifecycle = null
        }

        private companion object {
            /**
             * The Player→Transcript handoff briefly has no attached surface; this
             * window bridges it so continuous playback is preserved, while a real
             * navigation to Home/Playlist/Search still pauses promptly.
             */
            const val IDLE_PAUSE_MS = 400L
        }
    }

/** Unwrap a Context chain to its [ComponentActivity], or null if there isn't one. */
private tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
