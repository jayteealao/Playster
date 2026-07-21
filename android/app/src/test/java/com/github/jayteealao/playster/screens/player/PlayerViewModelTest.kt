package com.github.jayteealao.playster.screens.player

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.lifecycle.SavedStateHandle
import com.github.jayteealao.playster.SettingsManager
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.NotesRepository
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.data.firestore.SummaryRepository
import com.github.jayteealao.playster.data.youtube.YouTubeDescriptionSource
import com.github.jayteealao.playster.functions.SummaryFunctions
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TST-1 - [PlayerViewModel] had zero VM-level tests. This screen has the
 * lowest genuinely-testable ceiling of the 7: its `init` block
 * unconditionally calls `summaryRepository.getOnce(videoId)` (a real,
 * suspending Firestore `.get()`, not a listener) whenever `videoId` is
 * non-blank - and unlike a listener, a single `.get()` call is enough on its
 * own to start `FirestoreClient`'s async SQLite persistence layer (the exact
 * race documented on `com.github.jayteealao.playster.screens.home.HomeViewModelTest`),
 * *even with no subscriber ever attached to anything*. So a real (non-blank)
 * `videoId` cannot be used here at all without risking that same
 * intermittent `SQLiteCantOpenDatabaseException` - not just when subscribing
 * to `uiState`, but at construction time itself.
 *
 * That leaves only the blank-videoId construction path - the `init` guard's
 * `isNotBlank()` false branch - as safely testable. `createNote` (a real
 * write with no injectable sink) and `uiState`'s combine (real listeners) are
 * both out of reach for the same reasons as
 * `com.github.jayteealao.playster.screens.playlist.PlaylistViewModelTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerViewModelTest {
    private lateinit var app: Context
    private lateinit var firestoreRepository: FirestoreRepository
    private lateinit var progressRepository: ProgressRepository
    private lateinit var summaryRepository: SummaryRepository
    private lateinit var notesRepository: NotesRepository
    private lateinit var descriptionSource: YouTubeDescriptionSource
    private lateinit var summaryFunctions: SummaryFunctions
    private lateinit var settingsManager: SettingsManager
    private lateinit var playbackSession: PlaybackSession

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
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
        firestoreRepository = FirestoreRepository(firestore)
        progressRepository = ProgressRepository(app, firestore, authBridge)
        summaryRepository = SummaryRepository(firestore)
        notesRepository = NotesRepository(firestore, authBridge)
        descriptionSource = YouTubeDescriptionSource(app)
        summaryFunctions = SummaryFunctions(FirebaseFunctions.getInstance(FirebaseApp.getInstance()))
        settingsManager = SettingsManager(app)
        playbackSession = PlaybackSession { _, _, _, _, _ -> }
    }

    @Test
    fun construction_withNoVideoIdArg_doesNotThrow_andStartsLoading() {
        val viewModel =
            PlayerViewModel(
                savedStateHandle = SavedStateHandle(),
                firestoreRepository = firestoreRepository,
                progressRepository = progressRepository,
                summaryRepository = summaryRepository,
                notesRepository = notesRepository,
                descriptionSource = descriptionSource,
                summaryFunctions = summaryFunctions,
                settingsManager = settingsManager,
                playbackSession = playbackSession,
            )

        assertEquals(PlayerUiState.Loading, viewModel.uiState.value)
        assertEquals(1.0f, viewModel.defaultSpeed.value)
    }
}
