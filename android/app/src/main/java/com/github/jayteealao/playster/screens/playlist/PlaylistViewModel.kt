package com.github.jayteealao.playster.screens.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.NoteDoc
import com.github.jayteealao.playster.data.firestore.NotesRepository
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.data.firestore.QuotaDoc
import com.github.jayteealao.playster.data.firestore.QuotaRepository
import com.github.jayteealao.playster.data.firestore.SummaryRepository
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.navigation.EditorialRoutes
import com.github.jayteealao.playster.screens.common.state.toQuotaState
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import com.github.jayteealao.playster.screens.videoDetail.summary.mapSummaryDocToState
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
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * Assembles the Playlist (volume) view from the five real sources — the
 * playlist doc (cover), its own `videos` subcollection (episodes), the
 * reading-progress reads (watched/playing join), the representative episode's
 * summary, the quota doc, and this playlist's notes — through the pure
 * [PlaylistState.assemble]. This class is thin flow glue: `combine` the
 * sources, `flatMapLatest` onto the summary of the representative episode
 * (Assumption A1), map through the assembler, surface a terminal error as
 * [PlaylistUiState.Error], and start at [PlaylistUiState.Loading].
 *
 * The summary/quota *mapping* is the existing shared model (`mapSummaryDocToState`
 * / `toQuotaState`) reused verbatim so the product semantics the regression
 * flows guard cannot drift.
 */
@HiltViewModel
class PlaylistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val firestoreRepository: FirestoreRepository,
        private val progressRepository: ProgressRepository,
        private val summaryRepository: SummaryRepository,
        private val quotaRepository: QuotaRepository,
        private val notesRepository: NotesRepository,
    ) : ViewModel() {
        private val playlistId: String = savedStateHandle[EditorialRoutes.ARG_PLAYLIST_ID] ?: ""
        private val clock: Clock = Clock.systemDefaultZone()

        /** Bumping this re-subscribes every source — the error-notice retry path. */
        private val retryTrigger = MutableStateFlow(0)

        init {
            // DI-2: this is the shipped app's one "playlist opened" write —
            // without it, Home's `shelfProgressFlow` (kind == "playlist" order
            // by lastOpenedAt) has no producer in a real account and the shelf
            // stays permanently empty. Best-effort/fire-and-forget, mirroring
            // the other write sides here (a failure degrades quietly).
            if (playlistId.isNotBlank()) {
                viewModelScope.launch {
                    progressRepository.upsertPlaylistOpened(playlistId)
                }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<PlaylistUiState> =
            retryTrigger
                .flatMapLatest { playlistFlow() }
                .catch { emit(PlaylistUiState.Error) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = PlaylistUiState.Loading,
                )

        /** Re-collect all sources after a terminal error. */
        fun retry() {
            retryTrigger.value += 1
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun playlistFlow() =
            sourcesFlow()
                .flatMapLatest { sources ->
                    if (sources == null) {
                        flowOf(PlaylistUiState.Loading)
                    } else {
                        summaryStateFlow(sources).map { summary -> assemble(sources, summary) }
                    }
                }

        /**
         * The five non-summary sources into one [Sources] snapshot (or null
         * while the playlist doc hasn't resolved yet — Loading). Two `combine`
         * levels because six flows exceed the typed-arity limit, and the
         * playlist-kind progress doc supplies the cover's "last opened" instant.
         */
        private fun sourcesFlow() =
            combine(
                firestoreRepository.playlistsFlow(),
                firestoreRepository.videosFlow(playlistId),
                progressRepository.videoProgressFlow(),
                progressRepository.shelfProgressFlow(),
                notesRepository.notesByPlaylistFlow(playlistId),
            ) { playlists, videos, videoProgress, shelfProgress, notes ->
                val playlist = playlists.firstOrNull { it.id == playlistId } ?: return@combine null
                Sources(
                    playlist = playlist,
                    allPlaylists = playlists,
                    videos = videos,
                    videoProgress = videoProgress,
                    notes = notes,
                    lastOpenedMillis =
                        shelfProgress.firstOrNull { it.playlistId == playlistId }
                            ?.lastOpenedAt?.toDate()?.time,
                )
            }.combineQuota()

        private fun kotlinx.coroutines.flow.Flow<Sources?>.combineQuota() =
            combine(quotaRepository.observe()) { sources, quotaDoc ->
                sources?.copy(quotaDoc = quotaDoc)
            }

        /** Observe the representative episode's summary; no target → no summary. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun summaryStateFlow(sources: Sources): kotlinx.coroutines.flow.Flow<SummaryUiState> {
            val targetId =
                PlaylistState.summaryTargetId(sources.playlist, sources.videos, sources.videoProgress)
            return if (targetId == null) {
                flowOf(SummaryUiState.NoSummary)
            } else {
                summaryRepository.observe(targetId).map { mapSummaryDocToState(it) }
            }
        }

        private fun assemble(
            sources: Sources,
            summary: SummaryUiState,
        ): PlaylistUiState =
            PlaylistState.assemble(
                playlist = sources.playlist,
                allPlaylists = sources.allPlaylists,
                videos = sources.videos,
                videoProgress = sources.videoProgress,
                summary = summary,
                quotaExhausted = sources.quotaDoc.toQuotaState().isDisabled,
                notes = sources.notes,
                lastOpenedMillis = sources.lastOpenedMillis,
                clock = clock,
            )

        /** Non-summary source snapshot; the summary rides its own dynamic flow. */
        private data class Sources(
            val playlist: PlaylistDoc,
            val allPlaylists: List<PlaylistDoc>,
            val videos: List<VideoDoc>,
            val videoProgress: List<ProgressDoc>,
            val notes: List<NoteDoc>,
            val lastOpenedMillis: Long?,
            val quotaDoc: QuotaDoc? = null,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
