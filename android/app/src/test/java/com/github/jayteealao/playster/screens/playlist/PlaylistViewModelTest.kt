package com.github.jayteealao.playster.screens.playlist

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.lifecycle.SavedStateHandle
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.NotesRepository
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.data.firestore.QuotaRepository
import com.github.jayteealao.playster.data.firestore.SummaryRepository
import com.github.jayteealao.playster.navigation.EditorialRoutes
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
 * TST-1 - [PlaylistViewModel] had zero VM-level tests. The D2 wall is
 * breached the same way as the sibling VM tests (real repositories over a
 * dummy, offline [FirebaseApp] under Robolectric), but this screen's coverage
 * ceiling is lower than Home/Search/Transcript's, and it's worth recording
 * precisely why:
 *
 *  - The init-time write this finding's brief specifically asked about
 *    (`progressRepository.upsertPlaylistOpened(playlistId)`, DI-2) is NOT
 *    observable here: `ProgressRepository.progressCollection()` short-circuits
 *    to `null` whenever `authBridge.currentUid.value` is null (our always-
 *    signed-out test fixture, same as every other VM test in this module),
 *    so `upsertPlaylistOpened` returns immediately without ever attempting a
 *    write - there is nothing to assert on without either a real signed-in
 *    session (not achievable offline/deterministically) or a fake/mockable
 *    seam for `ProgressRepository` (both disallowed for this task).
 *  - `uiState`'s combine is never subscribed to here, for the same real-
 *    Firestore-listener/SQLite-async-race reason documented on
 *    `com.github.jayteealao.playster.screens.home.HomeViewModelTest` -
 *    notably, this screen has no `withOfflineFallback` gate at all (unlike
 *    Home/Search/Transcript), so subscribing offline would just hang on
 *    `Loading` forever rather than resolving to anything - not a
 *    behavior worth a flaky live-listener test either way.
 *
 * What IS genuinely testable: construction itself doesn't throw for either a
 * blank or a real playlistId (the two branches `init`'s `isNotBlank()` guard
 * takes), and `uiState`'s pre-subscription initial value is `Loading`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistViewModelTest {
    private lateinit var app: Context
    private lateinit var firestoreRepository: FirestoreRepository
    private lateinit var progressRepository: ProgressRepository
    private lateinit var summaryRepository: SummaryRepository
    private lateinit var quotaRepository: QuotaRepository
    private lateinit var notesRepository: NotesRepository

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
        quotaRepository = QuotaRepository(firestore)
        notesRepository = NotesRepository(firestore, authBridge)
    }

    private fun buildViewModel(playlistId: String?): PlaylistViewModel {
        val handle =
            if (playlistId != null) {
                SavedStateHandle(mapOf(EditorialRoutes.ARG_PLAYLIST_ID to playlistId))
            } else {
                SavedStateHandle()
            }
        return PlaylistViewModel(
            savedStateHandle = handle,
            firestoreRepository = firestoreRepository,
            progressRepository = progressRepository,
            summaryRepository = summaryRepository,
            quotaRepository = quotaRepository,
            notesRepository = notesRepository,
        )
    }

    @Test
    fun construction_withARealPlaylistId_doesNotThrow_andStartsLoading() {
        val viewModel = buildViewModel("playlist-1")
        assertEquals(PlaylistUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun construction_withNoPlaylistIdArg_doesNotThrow_andStartsLoading() {
        // EditorialRoutes.ARG_PLAYLIST_ID absent from the handle -> blank id
        // -> init's isNotBlank() guard skips the upsertPlaylistOpened launch
        // entirely. Must not crash.
        val viewModel = buildViewModel(null)
        assertEquals(PlaylistUiState.Loading, viewModel.uiState.value)
    }
}
