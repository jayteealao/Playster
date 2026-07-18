package com.github.jayteealao.playster.screens.player.playback

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants

private const val TAG = "playster.playback"
private const val HEX_RADIX = 16

/**
 * Playback dark-path instrumentation (04b-instrument.md §player), copied from
 * that artifact's §3 signal designs: a logcat mirror on the `playster.playback`
 * tag plus a Crashlytics non-fatal with low-cardinality custom keys. The
 * videoId is hashed before it ever leaves the device — never the raw id in a
 * custom key or a shared logcat line (04b §4 PII rule).
 *
 * `playback_error` distinguishes a real embed failure from the editorial empty
 * state it visually resembles; `playback_recovered` is the denominator that
 * makes the error rate interpretable without a metrics backend.
 */
object PlaybackInstrumentation {
    /** Stable, non-reversible-enough correlation hash for a videoId (04b §3). */
    fun hash(videoId: String): String = videoId.hashCode().toUInt().toString(HEX_RADIX)

    /** `playback_error{error_code,video_id_hash}` on an embed failure. */
    fun onPlayerError(
        error: PlayerConstants.PlayerError,
        videoId: String,
    ) {
        val vidHash = hash(videoId)
        Log.w(TAG, "playbackError{code=${error.name},videoId=$vidHash}")
        Firebase.crashlytics.apply {
            setCustomKey("error_code", error.name)
            setCustomKey("video_id_hash", vidHash)
            recordException(PlaybackException("embed playback failed: ${error.name}"))
        }
    }

    /**
     * `playback_load_timeout{offline,video_id_hash}` — the embed never reached
     * ready within the load window (a silent no-onReady/no-onError failure the
     * error-code signal above cannot see). `offline` separates the airplane-mode
     * case from an online load that stalled.
     */
    fun onLoadTimeout(
        videoId: String,
        offline: Boolean,
    ) {
        val vidHash = hash(videoId)
        Log.w(TAG, "playbackLoadTimeout{offline=$offline,videoId=$vidHash}")
        Firebase.crashlytics.apply {
            setCustomKey("error_code", if (offline) "LOAD_TIMEOUT_OFFLINE" else "LOAD_TIMEOUT")
            setCustomKey("video_id_hash", vidHash)
            recordException(PlaybackException("embed load timed out (offline=$offline)"))
        }
    }

    /** `playback_recovered{video_id_hash}` when a load succeeds after an error. */
    fun onPlayerRecovered(videoId: String) {
        val vidHash = hash(videoId)
        Log.i(TAG, "playbackRecovered{videoId=$vidHash}")
        Firebase.crashlytics.setCustomKey("video_id_hash", vidHash)
    }
}

/** Non-fatal marker recorded to Crashlytics for an embed playback failure. */
class PlaybackException(message: String) : Exception(message)
