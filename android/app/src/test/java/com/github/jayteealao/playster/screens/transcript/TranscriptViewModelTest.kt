package com.github.jayteealao.playster.screens.transcript

import androidx.credentials.CredentialManager
import androidx.lifecycle.SavedStateHandle
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.HighlightsRepository
import com.github.jayteealao.playster.data.firestore.NotesRepository
import com.github.jayteealao.playster.data.firestore.TranscriptRepository
import com.github.jayteealao.playster.navigation.EditorialRoutes
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TST-1 - [TranscriptViewModel] had zero VM-level tests. Same D2-wall breach
 * as [com.github.jayteealao.playster.screens.home.HomeViewModelTest]: real
 * repositories over a dummy, offline [FirebaseApp] under Robolectric, plus a
 * real [PlaybackSession] built with a no-op [com.github.jayteealao.playster.screens.player.playback.ProgressWriteSink]
 * lambda - the SAM injection seam this session's own fix added specifically
 * so a rendering/VM-level test never needs the live `ProgressRepository`
 * write path just to construct the shared session.
 *
 * REL-7's offline-fallback gate on `uiState`, `toggleHighlight`/`createNoteAt`
 * (real Firestore writes with no injectable sink - `HighlightsRepository`/
 * `NotesRepository` are final classes, unlike `PlaybackSession`'s narrowed
 * write dependency), and the out-of-doc signed-URL fetch path (a real
 * `HttpURLConnection` to an external host) are all deliberately NOT exercised
 * here. The offline gate specifically was attempted and dropped for the same
 * reason as `HomeViewModelTest`'s: subscribing to `uiState` attaches a real
 * `TranscriptRepository.observe(videoId)` Firestore listener, whose on-disk
 * SQLite persistence opens asynchronously and races Robolectric's per-test
 * sandbox recycling (confirmed empirically - see `HomeViewModelTest`'s doc
 * comment for the full account). TST-4 already covers
 * `TranscriptSegmentParser` at the pure layer for the signed-URL path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptViewModelTest {
    private lateinit var viewModel: TranscriptViewModel

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        if (FirebaseApp.getApps(app).isEmpty()) {
            FirebaseApp.initializeApp(
                app,
                FirebaseOptions.Builder()
                    .setApplicationId("1:1:android:1")
                    .setApiKey("test-api-key")
                    .setProjectId("test-project")
                    .build(),
            )
        }
        val firestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance())
        val auth = FirebaseAuth.getInstance(FirebaseApp.getInstance())
        val credentialManager = CredentialManager.create(app)
        val authBridge = FirebaseAuthBridge(auth, credentialManager)
        val transcriptRepository = TranscriptRepository(firestore)
        val highlightsRepository = HighlightsRepository(firestore, authBridge)
        val notesRepository = NotesRepository(firestore, authBridge)
        val firestoreRepository = FirestoreRepository(firestore)
        val playbackSession = PlaybackSession { _, _, _, _, _ -> }
        val savedStateHandle = SavedStateHandle(mapOf(EditorialRoutes.ARG_VIDEO_ID to "video-1"))

        viewModel =
            TranscriptViewModel(
                savedStateHandle = savedStateHandle,
                appContext = app,
                transcriptRepository = transcriptRepository,
                highlightsRepository = highlightsRepository,
                notesRepository = notesRepository,
                firestoreRepository = firestoreRepository,
                playbackSession = playbackSession,
            )
    }

    @Test
    fun initialState_isLoading() {
        // No subscriber ever attaches to uiState here, so this reads the
        // pre-collection initialValue - safe (no real Firestore listener
        // touched) and still a real DI-wiring/constructor regression test
        // (e.g. PlaybackSession.controllerFor is called eagerly in the
        // property initializer - this catches a break there too).
        assertEquals(TranscriptUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun header_startsNull_beforeTheVideoDocResolves() {
        assertEquals(null, viewModel.header.value)
    }
}
