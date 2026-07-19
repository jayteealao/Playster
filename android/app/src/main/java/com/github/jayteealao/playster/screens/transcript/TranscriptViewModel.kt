package com.github.jayteealao.playster.screens.transcript

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.HighlightsRepository
import com.github.jayteealao.playster.data.firestore.NotesRepository
import com.github.jayteealao.playster.data.firestore.TranscriptDoc
import com.github.jayteealao.playster.data.firestore.TranscriptRepository
import com.github.jayteealao.playster.data.firestore.TranscriptSegment
import com.github.jayteealao.playster.navigation.EditorialRoutes
import com.github.jayteealao.playster.screens.player.playback.PlaybackController
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.github.jayteealao.playster.screens.player.playback.isDeviceOffline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Assembles the Transcript from the transcript doc, the reader's highlights, and
 * this video's notes — through the pure [TranscriptStateAssembler] — and folds
 * the shared [PlaybackSession]'s live position into the active line (derived in
 * the UI, not here). Thin flow glue: `combine` the three sources, map through
 * the assembler, surface a terminal error, start at [TranscriptUiState.Loading].
 *
 * It owns the two write intents the screen drives — the idempotent highlight
 * toggle ([toggleHighlight], AC2) and note creation at the playback position
 * ([createNoteAt], reusing the player's [NotesRepository.createNote] so a note
 * appears in the Player and Playlist Notes tabs too, AC3) — plus the large-
 * transcript signed-URL fetch and its expiry recovery (AC6).
 *
 * The [controller] and the embed both come from the activity-shared session, so
 * a transcript reached from the Player inherits the *still-playing* embed, and
 * one reached fresh (deep link) cues its own.
 */
@HiltViewModel
@Suppress("LongParameterList") // Each source/collaborator is injected; none bundleable without a wrapper.
class TranscriptViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val appContext: Context,
        private val transcriptRepository: TranscriptRepository,
        private val highlightsRepository: HighlightsRepository,
        private val notesRepository: NotesRepository,
        private val firestoreRepository: FirestoreRepository,
        val playbackSession: PlaybackSession,
    ) : ViewModel() {
        private val videoId: String = savedStateHandle[EditorialRoutes.ARG_VIDEO_ID] ?: ""

        @Volatile private var resolvedPlaylistId: String = ""

        /**
         * The one shared controller for this video — the same instance the Player
         * holds if the reader came from there (playback continues), or a freshly
         * cued one on a deep-link entry. Read by the UI for the position stream,
         * the pill's play/pause, and tap-to-seek.
         */
        val controller: PlaybackController =
            playbackSession.controllerFor(
                videoId = videoId,
                startPositionSeconds = 0f,
                isOffline = { isDeviceOffline(appContext) },
            )

        /** The dateline, from the video document; null until it resolves. */
        val header: StateFlow<TranscriptHeader?> =
            firestoreRepository.videoContextFlow(videoId)
                .map { context ->
                    context?.video?.let { video ->
                        TranscriptHeader(
                            kicker = video.title.ifBlank { "Transcript" },
                            byline =
                                listOfNotNull(
                                    video.channelTitle.takeIf { it.isNotBlank() }?.let { "A conversation with $it" },
                                    video.publishedAt.takeIf { it.isNotBlank() },
                                ).joinToString(" · ").ifBlank { "" },
                        )
                    }
                }
                .catch { emit(null) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = null,
                )

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TranscriptUiState> =
            combine(
                resolvedTranscriptFlow(),
                highlightsRepository.highlightsByVideoFlow(videoId),
                notesFlow(),
            ) { resolution, highlights, notes ->
                TranscriptStateAssembler.assemble(
                    doc = resolution.doc,
                    resolvedSegments = resolution.segments,
                    highlights = highlights,
                    notes = notes,
                    fetchError = resolution.fetchError,
                )
            }.catch { emit(TranscriptUiState.Error(LISTEN_ERROR_MESSAGE)) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = TranscriptUiState.Loading,
                )

        /** Idempotent highlight toggle on the stable segment start (AC2). */
        fun toggleHighlight(paragraph: TranscriptParagraph) {
            viewModelScope.launch {
                if (paragraph.highlighted) {
                    highlightsRepository.removeHighlight(videoId, paragraph.segmentStart)
                } else {
                    highlightsRepository.addHighlight(videoId, paragraph.segmentStart, paragraph.text)
                }
            }
        }

        /** Create a note stamped at the current playback position (AC3). */
        fun createNoteAt(text: String) {
            viewModelScope.launch {
                notesRepository.createNote(
                    videoId = videoId,
                    playlistId = resolvedPlaylistId,
                    t = controller.positionSeconds.value.toDouble().coerceAtLeast(0.0),
                    text = text,
                )
            }
        }

        /** Seek the shared embed to a tapped paragraph's timestamp (AC1). */
        fun seekTo(segmentStart: Double) {
            controller.seekTo(segmentStart.toFloat())
        }

        /** Toggle play/pause from the mini-player pill (AC4). */
        fun togglePlayPause(playing: Boolean) {
            if (playing) controller.pause() else controller.play()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun notesFlow(): Flow<List<com.github.jayteealao.playster.data.firestore.NoteDoc>> =
            firestoreRepository.videoContextFlow(videoId).flatMapLatest { context ->
                val playlistId = context?.playlistId.orEmpty()
                resolvedPlaylistId = playlistId
                if (playlistId.isBlank()) {
                    flowOf(emptyList())
                } else {
                    notesRepository.notesByPlaylistFlow(playlistId)
                        .map { notes -> notes.filter { it.videoId == videoId } }
                }
            }

        /**
         * Resolves the segments to render. Inline `doc.segments` are used
         * directly; a large transcript stored out-of-doc behind a `signedUrl`
         * (Assumption 6) is fetched best-effort — a failure or an expired URL
         * surfaces as [Resolution.fetchError], which the assembler maps to the
         * editorial error (AC6), never a blank screen. A backend re-sign re-emits
         * the doc through the listener and re-drives this fetch.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun resolvedTranscriptFlow(): Flow<Resolution> =
            transcriptRepository.observe(videoId).flatMapLatest { doc ->
                val signedUrl = doc?.signedUrl
                when {
                    doc == null -> flowOf(Resolution(null, emptyList(), fetchError = false))
                    doc.segments.isNotEmpty() -> flowOf(Resolution(doc, doc.segments, fetchError = false))
                    signedUrl != null ->
                        flow {
                            emit(Resolution(doc, emptyList(), fetchError = false)) // loading while we fetch
                            val fetched = fetchSegments(signedUrl)
                            emit(Resolution(doc, fetched.orEmpty(), fetchError = fetched == null))
                        }
                    else -> flowOf(Resolution(doc, emptyList(), fetchError = false))
                }
            }

        /**
         * Best-effort GCS blob fetch for out-of-doc transcripts. Returns null on
         * any failure (network, 403/expired signed URL, malformed body) so the
         * caller can degrade to the editorial error. Runs off the main thread.
         *
         * sdlc-debt: naive `HttpURLConnection` + full-body read (ceiling: no
         * streaming, no retry/backoff). Fine for a one-shot best-effort read that
         * degrades editorially; upgrade to the app's http stack if this path
         * proves live against real large-transcript blobs at verify.
         */
        private suspend fun fetchSegments(signedUrl: String): List<TranscriptSegment>? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = (URL(signedUrl).openConnection() as HttpURLConnection)
                    try {
                        connection.connectTimeout = FETCH_TIMEOUT_MS
                        connection.readTimeout = FETCH_TIMEOUT_MS
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        parseSegments(body)
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }

        private fun parseSegments(body: String): List<TranscriptSegment> {
            val array = JSONArray(body)
            return (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val text = obj.optString("text", "")
                if (text.isEmpty()) return@mapNotNull null
                TranscriptSegment(start = obj.optDouble("start", 0.0), text = text)
            }
        }

        private data class Resolution(
            val doc: TranscriptDoc?,
            val segments: List<TranscriptSegment>,
            val fetchError: Boolean,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val FETCH_TIMEOUT_MS = 8_000
            const val LISTEN_ERROR_MESSAGE =
                "The transcript could not be reached. Try the recording instead."
        }
    }
