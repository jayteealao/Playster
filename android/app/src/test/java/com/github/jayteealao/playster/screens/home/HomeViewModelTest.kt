package com.github.jayteealao.playster.screens.home

import androidx.credentials.CredentialManager
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.ProgressRepository
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
 * TST-1 - [HomeViewModel] had zero VM-level tests. The recorded D2 wall
 * (`ProgressRepository`/`FirestoreRepository` are final classes over a live
 * `FirebaseFirestore`, no mocking library on the classpath) is breached the
 * same way `AuthViewModelTest`/`PlaybackControllerListenerTest` do it:
 * construct real repositories over a dummy, offline [FirebaseApp] under
 * Robolectric - no network, no live project, deterministic.
 *
 * REL-5's offline-fallback gate (`.withOfflineFallback(...)` wrapping
 * `uiState`'s `combine`) was attempted here too, driving the flow's
 * `WhileSubscribed` collection via a background subscriber and advancing
 * Robolectric's paused main `Looper` to resolve the watchdog's `delay(...)`
 * deterministically (the same technique `PlaybackControllerListenerTest`
 * uses for its Crashlytics calls, extended to a coroutine timer). It is
 * deliberately NOT included: subscribing actually attaches a *real*
 * `addSnapshotListener` against `FirebaseFirestore`'s on-disk (SQLite)
 * persistence layer, which opens its database file asynchronously on its own
 * background thread - independent of anything on the main Looper. Confirmed
 * empirically, repeatedly, and worsening under load: this races Robolectric's
 * per-test sandbox directory recycling and throws
 * `SQLiteCantOpenDatabaseException` intermittently (and, once three VM test
 * classes each attempt it in the same JVM, consistently) when run alongside
 * `SearchViewModelTest`/`TranscriptViewModelTest` - a genuine async native-
 * persistence race, not a fixable timing constant. Per-test-class database
 * isolation (`FirebaseFirestore.getInstance(app, uniqueDbId)`) and explicit
 * synchronous `firestore.terminate()` in `@After` were both tried and did not
 * make it reliable. This is a real technical wall distinct from D2's "no
 * mocking library" one: fixing it would need either a mocking library
 * (disallowed) or an interface extraction to substitute a fake repository
 * (disallowed - "no interface extractions purely for tests").
 *
 * The offline-fallback *logic* itself (`withOfflineFallback`'s watchdog/
 * isOffline/fallback contract) is already covered deterministically and
 * thoroughly at the pure-operator layer by
 * `com.github.jayteealao.playster.screens.player.playback.OfflineFlowGateTest`,
 * against synthetic flows with no Firestore involved at all - that is where
 * this behavior's real test coverage lives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {
    private lateinit var viewModel: HomeViewModel

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
        val firestoreRepository = FirestoreRepository(firestore)
        val progressRepository = ProgressRepository(app, firestore, authBridge)
        viewModel = HomeViewModel(firestoreRepository, progressRepository, app)
    }

    @Test
    fun initialState_isLoading() {
        // uiState is a StateFlow built with stateIn(WhileSubscribed(...)) - no
        // subscriber ever attaches here, so this reads the pre-collection
        // initialValue and never touches a real Firestore listener. Still a
        // real regression test: it catches a DI-wiring/constructor break
        // (e.g. a bad SavedStateHandle/context access) that would otherwise
        // only surface at runtime.
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
    }
}
