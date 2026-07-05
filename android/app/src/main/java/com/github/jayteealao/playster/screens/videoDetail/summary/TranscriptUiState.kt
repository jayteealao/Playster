package com.github.jayteealao.playster.screens.videoDetail.summary

/**
 * Four-state UI model for the transcript section on SummaryScreen.
 *
 * [Loading] covers both "no doc yet" (video not yet backfilled) and "pending"
 * status (fetch in progress). Both are transient states that will resolve to one
 * of the three terminal states, so they share one affordance rather than splitting
 * into a fifth state with no user-observable difference.
 *
 * [Available] carries display-ready segments already mapped from the pointer doc.
 * The ViewModel maps these directly from the Firestore `segments` field — no
 * additional HTTP round-trip is needed for the segment data. The [signedUrl] is
 * carried as a "View full transcript" link for future use (v1 residual).
 *
 * [Error] covers transient/error/too-large pointer-doc statuses AND any Flow error.
 * It is non-blocking: rendered as a text line in the column, not a modal overlay.
 */
sealed interface TranscriptUiState {
    data object Loading : TranscriptUiState

    data class Available(
        val segments: List<TranscriptSegmentUi>,
        val language: String?,
        val signedUrl: String?,
    ) : TranscriptUiState

    data object Unavailable : TranscriptUiState

    data class Error(val message: String) : TranscriptUiState
}

/**
 * Display-ready view of one transcript segment. [startSeconds] is the raw
 * offset used by [formatTimestamp]; [text] is the spoken words.
 */
data class TranscriptSegmentUi(
    val startSeconds: Double,
    val text: String,
)

/** Formats an offset in seconds as M:SS (e.g. 83.0 → "1:23"). */
internal fun formatTimestamp(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val minutes = total / 60
    val secs = total % 60
    return "%d:%02d".format(minutes, secs)
}
