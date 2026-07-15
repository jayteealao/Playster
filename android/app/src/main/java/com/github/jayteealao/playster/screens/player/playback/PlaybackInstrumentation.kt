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

    /** `playback_recovered{video_id_hash}` when a load succeeds after an error. */
    fun onPlayerRecovered(videoId: String) {
        val vidHash = hash(videoId)
        Log.i(TAG, "playbackRecovered{videoId=$vidHash}")
        Firebase.crashlytics.setCustomKey("video_id_hash", vidHash)
    }
}

/** Non-fatal marker recorded to Crashlytics for an embed playback failure. */
class PlaybackException(message: String) : Exception(message)
