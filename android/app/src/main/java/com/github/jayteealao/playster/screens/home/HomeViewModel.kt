package com.github.jayteealao.playster.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import javax.inject.Inject

/**
 * Assembles the Home front page from the playlist collection and the
 * reading-progress read repository. Everything data-shaped is delegated to the
 * pure [HomeState.assemble] / [com.github.jayteealao.playster.data.editorial.EditorialDressing]
 * — this class is thin flow glue: `combine` the four sources, map through the
 * assembler, surface a terminal error as [HomeUiState.Error], and start at
 * [HomeUiState.Loading].
 *
 * The clock is a plain `systemDefaultZone` rather than a Hilt binding (the
 * slice adds no `AppModule` provider); the derivation's determinism is proven
 * against a fixed clock at the pure-function layer (AC1).
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val firestoreRepository: FirestoreRepository,
        private val progressRepository: ProgressRepository,
    ) : ViewModel() {
        private val clock: Clock = Clock.systemDefaultZone()

        /** Bumping this re-subscribes every source — the error-notice retry path. */
        private val retryTrigger = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<HomeUiState> =
            retryTrigger
                .flatMapLatest { homeFlow() }
                .catch { emit(HomeUiState.Error) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = HomeUiState.Loading,
                )

        /** Re-collect all sources after a terminal error. */
        fun retry() {
            retryTrigger.value += 1
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun homeFlow() =
            combine(
                firestoreRepository.playlistsFlow(),
                progressRepository.shelfProgressFlow(),
                progressRepository.videoProgressFlow(),
                continueDetailFlow(),
            ) { playlists, shelfProgress, videoProgress, headliner ->
                HomeState.assemble(
                    playlists = playlists,
                    shelfProgress = shelfProgress,
                    videoProgress = videoProgress,
                    continueHeadliner = headliner,
                    clock = clock,
                )
            }

        /**
         * The continue headliner joined to its video document: the most recent
         * video-progress doc (Q2, limit 1) paired with `videoFlow(videoId)`, or
         * null when no video progress exists yet.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun continueDetailFlow() =
            progressRepository.continueVideoFlow().flatMapLatest { progress ->
                if (progress == null || progress.videoId.isBlank()) {
                    flowOf(null)
                } else {
                    firestoreRepository.videoFlow(progress.videoId).map { video -> progress to video }
                }
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
