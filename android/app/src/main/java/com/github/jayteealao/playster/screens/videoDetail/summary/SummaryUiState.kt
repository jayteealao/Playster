package com.github.jayteealao.playster.screens.videoDetail.summary

/**
 * Five-state UI model for the SummaryScreen. The ViewModel maps Firestore
 * snapshots of `summaries/{videoId}` and callable-invocation failures into
 * exactly one of these states. Composables render with `when` exhaustively.
 */
sealed interface SummaryUiState {
    data object NoSummary : SummaryUiState

    data object InProgress : SummaryUiState

    data class Completed(val content: String, val model: String) : SummaryUiState

    data class FailedTransient(val message: String) : SummaryUiState

    data class FailedPermanent(val message: String) : SummaryUiState
}
