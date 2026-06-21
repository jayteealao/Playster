package com.github.jayteealao.playster.screens.videoDetail.summary

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.SummaryDoc
import com.github.jayteealao.playster.data.firestore.SummaryRepository
import com.github.jayteealao.playster.functions.SummaryFunctions
import com.github.jayteealao.playster.screens.common.state.SummaryStatus
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "playster.summary"

@HiltViewModel
class SummaryViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val summaryRepository: SummaryRepository,
        private val summaryFunctions: SummaryFunctions,
    ) : ViewModel() {
        val videoId: String = savedStateHandle["videoId"] ?: ""
        private val autoDispatch: Boolean = savedStateHandle["autoDispatch"] ?: false

        /**
         * Local override that takes precedence over the listener-derived state.
         * Used when a callable invocation fails before any doc is reserved —
         * the listener will emit `null` forever in that case, so the UI needs
         * this bridge to surface the transient error.
         */
        private val localFailure = MutableStateFlow<SummaryUiState?>(null)
        private val autoDispatched = MutableStateFlow(false)

        val uiState: StateFlow<SummaryUiState> =
            summaryRepository
                .observe(videoId)
                .combine(localFailure) { doc, override ->
                    override ?: mapDocToState(doc)
                }
                .catch { err ->
                    Log.w(TAG, "observe closed with error for $videoId", err)
                    emit(SummaryUiState.FailedTransient("Couldn't load summary. Try again."))
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SummaryUiState.NoSummary,
                )

        init {
            if (autoDispatch) {
                // One-shot: if the listener emits no doc, fire the callable. We
                // don't `collect` here — we just await first non-null emission
                // via getOnce so we don't race with the listener.
                viewModelScope.launch {
                    val existing = summaryRepository.getOnce(videoId)
                    if (existing == null && autoDispatched.compareAndSet(false, true)) {
                        dispatch()
                    }
                }
            }
        }

        fun retry() {
            localFailure.value = null
            viewModelScope.launch { dispatch() }
        }

        private suspend fun dispatch() {
            Log.d(TAG, "state{action=dispatch,videoId=$videoId}")
            val result = summaryFunctions.requestSummary(videoId)
            result.onFailure { err ->
                val ui = mapCallableErrorToState(err)
                Log.d(TAG, "state{action=dispatchFailed,videoId=$videoId,ui=${ui::class.simpleName}}")
                localFailure.value = ui
            }
        }

        private fun mapDocToState(doc: SummaryDoc?): SummaryUiState {
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

        private fun mapCallableErrorToState(err: Throwable): SummaryUiState {
            if (err is FirebaseFunctionsException) {
                return when (err.code) {
                    FirebaseFunctionsException.Code.UNAUTHENTICATED,
                    FirebaseFunctionsException.Code.PERMISSION_DENIED,
                    ->
                        SummaryUiState.FailedPermanent("Sign-in required.")
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                        SummaryUiState.FailedPermanent(
                            "Daily summary limit reached. Resets at midnight UTC.",
                        )
                    FirebaseFunctionsException.Code.NOT_FOUND ->
                        SummaryUiState.FailedPermanent("Video unavailable.")
                    FirebaseFunctionsException.Code.INTERNAL,
                    FirebaseFunctionsException.Code.UNAVAILABLE,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                    ->
                        SummaryUiState.FailedTransient(
                            err.message ?: "Couldn't summarize. Try again.",
                        )
                    else ->
                        SummaryUiState.FailedTransient(
                            err.message ?: "Couldn't summarize. Try again.",
                        )
                }
            }
            return SummaryUiState.FailedTransient(err.message ?: "Couldn't summarize. Try again.")
        }
    }
