package com.github.jayteealao.playster.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.search.RecentSearchesRepository
import com.github.jayteealao.playster.functions.SearchFunctions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The hybrid Search glue. Two legs run side by side over one query flow: the
 * INSTANT title half is a pure `combine` of the playlist + all-videos corpus and
 * the query through [SearchStateAssembler] — it never touches the network, which
 * is exactly AC1's promise; the BACKEND half is a debounced, min-2-char call to
 * [SearchFunctions] carrying its own sub-state so a failure degrades the
 * transcript group alone (R2). Recents are recorded on a committed search and
 * read back from the local [RecentSearchesRepository] (AC4).
 */
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val firestoreRepository: FirestoreRepository,
        private val searchFunctions: SearchFunctions,
        private val recentSearchesRepository: RecentSearchesRepository,
    ) : ViewModel() {
        private val queryFlow = MutableStateFlow("")

        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        private val transcriptLegFlow =
            queryFlow
                .map { it.trim() }
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.length < MIN_QUERY_CHARS) {
                        flowOf<TranscriptLeg>(TranscriptLeg.Idle)
                    } else {
                        // A committed search (produced the backend leg) is a recent (AC4).
                        viewModelScope.launch { recentSearchesRepository.record(query) }
                        flow {
                            emit(TranscriptLeg.Loading)
                            val result = searchFunctions.search(query)
                            emit(
                                result.fold(
                                    onSuccess = { TranscriptLeg.Hits(it) },
                                    onFailure = { TranscriptLeg.Error(TRANSCRIPT_ERROR_MESSAGE) },
                                ),
                            )
                        }
                    }
                }

        val uiState: StateFlow<SearchUiState> =
            combine(
                firestoreRepository.playlistsFlow(),
                firestoreRepository.allVideosWithContextFlow(),
                queryFlow,
                transcriptLegFlow,
                recentSearchesRepository.recent,
            ) { playlists, videos, query, leg, recents ->
                val titles = SearchStateAssembler.matchTitles(query, playlists, videos)
                SearchUiState(
                    query = query,
                    transcript = SearchStateAssembler.resolveTranscript(leg, videos),
                    videos = titles.videos,
                    playlists = titles.playlists,
                    recents = recents,
                )
            }.catch {
                // A corpus-listener failure must not blank the screen: keep the
                // query + a transcript-group error, drop the title half.
                emit(
                    SearchUiState.Initial.copy(
                        query = queryFlow.value,
                        transcript = TranscriptSearchState.Error(TRANSCRIPT_ERROR_MESSAGE),
                    ),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SearchUiState.Initial,
            )

        /** As-you-type: re-runs the instant half immediately; debounces the backend half. */
        fun onQueryChange(query: String) {
            queryFlow.value = query
        }

        /** The clear (×) affordance — empties the field and both legs. */
        fun onClearQuery() {
            queryFlow.value = ""
        }

        /** Tapping a recent pill re-runs it. */
        fun onRecentTap(query: String) {
            queryFlow.value = query
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val DEBOUNCE_MS = 300L
            const val MIN_QUERY_CHARS = 2
            const val TRANSCRIPT_ERROR_MESSAGE =
                "Transcript search is unavailable right now. Title results are still shown."
        }
    }
