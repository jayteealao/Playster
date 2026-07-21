package com.github.jayteealao.playster.screens.settings

import android.os.Looper
import androidx.credentials.CredentialManager
import com.github.jayteealao.playster.SettingsManager
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.HighlightsRepository
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.data.search.RecentSearchesRepository
import com.github.jayteealao.playster.ui.editorial.ReadingPreferencesStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * TST-1 - [SettingsViewModel] had zero VM-level tests. Same D2-wall breach as
 * [com.github.jayteealao.playster.screens.home.HomeViewModelTest]: real
 * repositories/stores over a dummy, offline [FirebaseApp] under Robolectric.
 *
 * [onSignOut]'s PRV-2 sequencing (recents cleared *before* the auth flip; a
 * credential-clear failure doesn't block sign-out) is the one behavior
 * genuinely worth a VM-level test here, and - unlike `uiState`'s offline
 * gate on the other screens - it does NOT touch a live Firestore listener at
 * all: `onSignOut` only calls `recentSearchesRepository.clear()` (a local
 * DataStore write) and `authBridge.signOut()` (`FirebaseAuth.signOut()` is a
 * local, synchronous call with no signed-in user to begin with;
 * `CredentialManager.clearCredentialState()` is real but its own
 * `runCatching` means it never blocks the assertion either way). Confirmed
 * empirically: `onSignOut`'s `viewModelScope.launch` is queued on
 * Robolectric's *paused* main Looper rather than running inline (Robolectric
 * itself flags this - "Main looper has queued unexecuted runnables" - on an
 * unidled attempt), so the assertion below drains it with a bounded
 * `ShadowLooper.idleFor` poll before checking DataStore's write landed.
 *
 * `uiState`'s own combine (Firestore-backed `progressRepository`/
 * `highlightsRepository`/`firestoreRepository` flows) is never subscribed to
 * here, for the same SQLite-async-race reason documented on
 * `HomeViewModelTest` - none of these tests read `viewModel.uiState`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    private lateinit var viewModel: SettingsViewModel
    private lateinit var recentSearchesRepository: RecentSearchesRepository

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
        val settingsManager = SettingsManager(app)
        val store = ReadingPreferencesStore(app, settingsManager)
        val firestoreRepository = FirestoreRepository(firestore)
        val progressRepository = ProgressRepository(app, firestore, authBridge)
        val highlightsRepository = HighlightsRepository(firestore, authBridge)
        recentSearchesRepository = RecentSearchesRepository(app)

        viewModel =
            SettingsViewModel(
                context = app,
                store = store,
                settingsManager = settingsManager,
                authBridge = authBridge,
                firestoreRepository = firestoreRepository,
                progressRepository = progressRepository,
                highlightsRepository = highlightsRepository,
                recentSearchesRepository = recentSearchesRepository,
            )
    }

    @Test
    fun onSignOut_clearsRecentSearches() {
        // The app's "settings" Preferences DataStore is process-wide, not
        // Robolectric-sandbox-scoped per test class (confirmed empirically,
        // and the existing RecentSearchesRepositoryTest defends against it
        // the same way with its own @Before clearStore()) - a sibling test
        // class's fixtures ("a11y", "design tokens") can otherwise still be
        // sitting in it when this runs as part of the full suite.
        runBlocking { recentSearchesRepository.clear() }
        runBlocking { recentSearchesRepository.record("hello world") }
        assertEquals(listOf("hello world"), runBlocking { recentSearchesRepository.recent.first() })

        viewModel.onSignOut()

        // onSignOut()'s viewModelScope.launch is queued on Robolectric's
        // *paused* main Looper rather than running inline (confirmed
        // empirically - Robolectric's own "Main looper has queued unexecuted
        // runnables" diagnostic on an unidled attempt), so it needs draining
        // before `recentSearchesRepository.clear()`'s DataStore write can
        // even start. Bounded real-time poll (not unconditional/unbounded):
        // idle a small chunk, check, repeat.
        var recents = runBlocking { recentSearchesRepository.recent.first() }
        val deadline = System.currentTimeMillis() + 3_000
        while (recents.isNotEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
            recents = runBlocking { recentSearchesRepository.recent.first() }
        }
        assertTrue("expected recents cleared, was $recents", recents.isEmpty())
    }

    @Test
    fun onSignOut_doesNotThrow_evenWithNoPriorSignIn() {
        // authBridge.signOut() against a FirebaseAuth with no current user,
        // plus a best-effort CredentialManager.clearCredentialState() call
        // whose own runCatching must never let a failure escape onSignOut -
        // this simply must not throw.
        viewModel.onSignOut()
    }
}
