package com.github.jayteealao.playster.screens.search

import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.search.RecentSearchesRepository
import com.github.jayteealao.playster.functions.SearchFunctions
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
 * TST-1/TST-14 - [SearchViewModel] had zero VM-level tests; Search's
 * functions-down degradation was only proxy-tested at the pure
 * [SearchStateAssembler] layer. Same D2-wall breach as
 * [com.github.jayteealao.playster.screens.home.HomeViewModelTest]: real
 * repositories/functions client over a dummy, offline [FirebaseApp] under
 * Robolectric.
 *
 * Two things are deliberately NOT exercised here, both for the same reason -
 * genuine nondeterminism this task's "deterministic tests only" rule rules
 * out:
 *  - `onQueryChange`/`onRecentTap` with a >=2-char query: that path calls
 *    `SearchFunctions.search(...)`, a real `FirebaseFunctions` HTTPS-callable
 *    round trip with no injectable seam.
 *  - The REL-6 offline-fallback gate on `uiState`: subscribing to it attaches
 *    a real Firestore `addSnapshotListener`, whose on-disk SQLite
 *    persistence opens asynchronously on its own background thread and races
 *    Robolectric's per-test sandbox recycling - confirmed empirically (see
 *    `HomeViewModelTest`'s doc comment for the full account, including the
 *    mitigations tried and rejected). The offline-fallback *logic* itself is
 *    covered deterministically at the pure-operator layer by
 *    `com.github.jayteealao.playster.screens.player.playback.OfflineFlowGateTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchViewModelTest {
    private lateinit var viewModel: SearchViewModel

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
        val firestoreRepository = FirestoreRepository(firestore)
        val functions = FirebaseFunctions.getInstance(FirebaseApp.getInstance())
        val searchFunctions = SearchFunctions(functions)
        val recentSearchesRepository = RecentSearchesRepository(app)
        viewModel = SearchViewModel(firestoreRepository, searchFunctions, recentSearchesRepository, app)
    }

    @Test
    fun initialState_isInitial() {
        // No subscriber ever attaches to uiState here, so this reads the
        // pre-collection initialValue - safe (no real Firestore listener
        // touched) and still a real DI-wiring/constructor regression test.
        assertEquals(SearchUiState.Initial, viewModel.uiState.value)
    }
}
