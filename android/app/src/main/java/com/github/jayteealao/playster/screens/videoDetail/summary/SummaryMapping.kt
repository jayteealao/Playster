package com.github.jayteealao.playster.screens.videoDetail.summary

import com.github.jayteealao.playster.data.firestore.SummaryDoc
import com.github.jayteealao.playster.screens.common.state.SummaryStatus

/**
 * The single wire → [SummaryUiState] mapping, extracted so every consumer — the
 * per-video [SummaryViewModel] and the Playlist Summary tab — maps a
 * `summaries/{videoId}` doc identically. Re-implementing it anywhere would risk
 * drifting the product semantics the cached-summary / quota regression flows
 * guard, so both call this one function.
 */
fun mapSummaryDocToState(doc: SummaryDoc?): SummaryUiState {
    val status = doc?.let { SummaryStatus.fromWire(it.statusWire) } ?: SummaryStatus.UNKNOWN
    return when (status) {
        SummaryStatus.UNKNOWN -> SummaryUiState.NoSummary
        SummaryStatus.QUEUED, SummaryStatus.PENDING, SummaryStatus.RUNNING -> SummaryUiState.InProgress
        SummaryStatus.COMPLETED ->
            SummaryUiState.Completed(
                content = doc?.content.orEmpty().ifBlank { "(empty summary)" },
                model = doc?.model ?: "free",
            )
        SummaryStatus.FAILED_TRANSIENT ->
            SummaryUiState.FailedTransient(
                message = doc?.errorMessage ?: "Couldn't summarize. Try again.",
            )
        SummaryStatus.FAILED_PERMANENT ->
            SummaryUiState.FailedPermanent(
                message = doc?.errorMessage ?: "This video can't be summarized.",
            )
    }
}
