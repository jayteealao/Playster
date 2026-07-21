package com.github.jayteealao.playster.screens.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.SettingsManager
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.NotesRepository
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.data.firestore.SummaryDoc
import com.github.jayteealao.playster.data.firestore.SummaryRepository
import com.github.jayteealao.playster.data.firestore.VideoWithContext
import com.github.jayteealao.playster.data.youtube.YouTubeDescriptionSource
import com.github.jayteealao.playster.functions.SummaryFunctions
import com.github.jayteealao.playster.navigation.EditorialRoutes
import com.github.jayteealao.playster.screens.player.chapters.ChaptersResolver
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.github.jayteealao.playster.screens.videoDetail.summary.mapSummaryDocToState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Assembles the Player from the video (+ derived playlist context), the resume
 * progress, the summary, the resolved chapters, and this video's notes — through
 * the pure [PlayerStateAssembler]. Thin flow glue: `combine` the sources,
 * `flatMapLatest` onto the video's notes, map through the assembler, surface a
 * terminal error as [PlayerUiState.Error], start at [PlayerUiState.Loading].
 *
 * It also owns note creation ([createNote] — AC8) and, once the playlist
 * context resolves, hands it to [PlaybackSession] ([PlaybackSession.bindPlaylistId])
 * — the throttled progress write itself now lives on the shared session (BC-1),
 * so it keeps running across Player↔Transcript navigation rather than stopping
 * whenever this screen leaves composition. Chapters fetch the description
 * client-side (Assumption 1) and degrade to the summarizer/empty source, so the
 * Chapters tab never blocks on the network.
 */
@HiltViewModel
// Each source/write-side is an injected collaborator; none bundleable without a wrapper.
@Suppress("LongParameterList")
class PlayerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val firestoreRepository: FirestoreRepository,
        private val progressRepository: ProgressRepository,
        private val summaryRepository: SummaryRepository,
        private val notesRepository: NotesRepository,
        private val descriptionSource: YouTubeDescriptionSource,
        private val summaryFunctions: SummaryFunctions,
        settingsManager: SettingsManager,
        /**
         * The activity-shared embed. Exposed so the screen can render the one
         * retained player and so it is the *same* instance the Transcript route
         * consumes — the mechanism behind a continuously visible mini-embed that
         * survives Player→Transcript navigation.
         */
        val playbackSession: PlaybackSession,
    ) : ViewModel() {
        private val videoId: String = savedStateHandle[EditorialRoutes.ARG_VIDEO_ID] ?: ""
        private val navPlaylistId: String? = savedStateHandle[EditorialRoutes.ARG_PLAYLIST_ID]

        /**
         * The Settings default-speed preference (AC5). The screen seeds the initial
         * playback speed from this and applies it once the embed is ready; the
         * controller snaps it to the nearest supported YouTube rate. Absent → 1.0×.
         */
        val defaultSpeed: StateFlow<Float> =
            settingsManager.defaultSpeed.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = 1.0f,
            )

        private val retryTrigger = MutableStateFlow(0)

        @Volatile private var resolvedPlaylistId: String = navPlaylistId.orEmpty()

        init {
            // Reuse the app's summary machinery: if this episode has no summary
            // yet, request one once on open so the Summary tab fills with real
            // data (the old auto-dispatch behavior, now editorial — closes the
            // fresh-summary regression the playlist slice owed here). Best-effort;
            // a failure surfaces through the observed summary state.
            if (videoId.isNotBlank()) {
                viewModelScope.launch {
                    if (summaryRepository.getOnce(videoId) == null) {
                        summaryFunctions.requestSummary(videoId)
                    }
                }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<PlayerUiState> =
            retryTrigger
                .flatMapLatest { playerFlow() }
                .catch { emit(PlayerUiState.Error) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = PlayerUiState.Loading,
                )

        fun retry() {
            retryTrigger.value += 1
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun playerFlow(): Flow<PlayerUiState> =
            combine(
                firestoreRepository.videoContextFlow(videoId),
                firestoreRepository.playlistsFlow(),
                progressRepository.videoProgressFlow(),
                summaryRepository.observe(videoId),
                descriptionFlow(),
            ) { context, playlists, videoProgress, summaryDoc, description ->
                Sources(context, playlists, videoProgress, summaryDoc, description)
            }.flatMapLatest { sources ->
                val context = sources.context ?: return@flatMapLatest flowOf(PlayerUiState.Loading)
                val playlistId = navPlaylistId?.takeIf { it.isNotBlank() } ?: context.playlistId
                resolvedPlaylistId = playlistId
                // BC-1: the shared session only ever sees a bare videoId at
                // controllerFor time — hand it the resolved playlistId as soon
                // as it's known so its own progress-write throttle (which now
                // runs for as long as playback continues, across Player↔
                // Transcript nav) can satisfy isValidProgress's playlistId field.
                playbackSession.bindPlaylistId(videoId, playlistId)
                notesRepository.notesByPlaylistFlow(playlistId).map { notes ->
                    assemble(sources, context, playlistId, notes.filter { it.videoId == videoId })
                }
            }

        /** Client-side description: null immediately (fall back fast), then the fetched value. */
        private fun descriptionFlow(): Flow<String?> =
            flow {
                emit(null)
                emit(descriptionSource.descriptionFor(videoId))
            }

        private fun assemble(
            sources: Sources,
            context: VideoWithContext,
            playlistId: String,
            videoNotes: List<com.github.jayteealao.playster.data.firestore.NoteDoc>,
        ): PlayerUiState {
            val playlist = sources.playlists.firstOrNull { it.id == playlistId }
            val progress = sources.videoProgress.firstOrNull { it.videoId == videoId }
            val chapters =
                ChaptersResolver.resolve(sources.description, sources.summaryDoc?.chapters.orEmpty())
            return PlayerStateAssembler.assemble(
                video = context.video,
                playlist = playlist,
                progress = progress,
                chapters = chapters,
                notes = videoNotes,
                summary = mapSummaryDocToState(sources.summaryDoc),
            )
        }

        /** Create a note stamped at [atSeconds] — appears in both Notes tabs (AC8). */
        fun createNote(
            atSeconds: Float,
            text: String,
        ) {
            viewModelScope.launch {
                notesRepository.createNote(
                    videoId = videoId,
                    playlistId = resolvedPlaylistId,
                    t = atSeconds.toDouble().coerceAtLeast(0.0),
                    text = text,
                )
            }
        }

        private data class Sources(
            val context: VideoWithContext?,
            val playlists: List<com.github.jayteealao.playster.data.firestore.PlaylistDoc>,
            val videoProgress: List<com.github.jayteealao.playster.data.firestore.ProgressDoc>,
            val summaryDoc: SummaryDoc?,
            val description: String?,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
