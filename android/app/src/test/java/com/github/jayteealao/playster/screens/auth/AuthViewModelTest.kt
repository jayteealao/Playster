package com.github.jayteealao.playster.screens.auth

import androidx.credentials.CredentialManager
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TST-5 — the sign-in failure-type -> [AuthUiState] mapping ([AuthViewModel]),
 * previously untested; the "state matrix + session-gate routing test" the
 * implement-stage deviation record claimed covers it does not, in fact, exist
 * for this specific mapping (only [EditorialSessionGateTest]'s `loggedIn`
 * boolean routing does).
 *
 * The D2 wall this hits: [FirebaseAuthBridge] is a final class over live
 * `FirebaseAuth`/`CredentialManager` types, and the task brief forbids adding a
 * mocking library (no mockk on the classpath — confirmed absent from
 * `gradle/libs.versions.toml`), so a `FakeFirebaseAuthBridge` substitutable via
 * DI is not an option without widening the constructor to an interface — a
 * production refactor beyond this fix's scope.
 *
 * What *is* testable without one: constructing a *real* [FirebaseAuthBridge]
 * over a real (but dummy, offline) [FirebaseApp] under Robolectric. This is
 * deterministic and touches no real network or real Firebase project —
 * `FirebaseAuth.getInstance()`/`CredentialManager.create()` only require a
 * `FirebaseApp`/`Context` to construct, and with no prior sign-in the resulting
 * `currentUser` is always null (confirmed empirically). That gives a real,
 * working [AuthViewModel] whose `onSignInCancelled`/`onSignInFailed` entry
 * points — the actual state-mapping logic this finding is about — are exercised
 * for real, no fake needed.
 *
 * What remains genuinely untestable under this constraint: `onGoogleIdToken`'s
 * own try/catch (`AuthViewModel.kt:65-77`) is not driven directly, because its
 * success path calls `firebaseAuth.signInWithCredential(...).await()`, a real
 * network call to the Firebase Auth backend — exactly what "no real network"
 * rules out, and there is no seam to substitute it without the mocking library
 * or interface refactor above. Its catch branch (`Failed(FailureKind.Bridge)`)
 * is instead verified by driving [AuthViewModel.onSignInFailed] with
 * [FailureKind.Bridge] directly: an identical `_uiState.value = Failed(kind)`
 * statement to the one the catch block executes, just reached through the
 * bridge's own public verb (the same verb `AuthScreen`'s catch-all mapping
 * uses) rather than through a real Firebase failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthViewModelTest {
    private lateinit var viewModel: AuthViewModel

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
        val auth = FirebaseAuth.getInstance(FirebaseApp.getInstance())
        val credentialManager = CredentialManager.create(app)
        viewModel = AuthViewModel(FirebaseAuthBridge(auth, credentialManager))
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(AuthUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun onSignInStarted_movesToAuthenticating() {
        viewModel.onSignInStarted()
        assertEquals(AuthUiState.Authenticating, viewModel.uiState.value)
    }

    @Test
    fun onSignInCancelled_afterAuthenticating_returnsToIdleWithoutAnErrorFlash() {
        viewModel.onSignInStarted()

        viewModel.onSignInCancelled()

        // A dismissed credential sheet is a user choice, not a failure - it must
        // map back to Idle, never to Failed (no error copy flashes).
        assertEquals(AuthUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun onSignInFailed_noCredential_mapsToTheDocumentedFailedState() {
        viewModel.onSignInFailed(FailureKind.NoCredential)
        assertEquals(AuthUiState.Failed(FailureKind.NoCredential), viewModel.uiState.value)
    }

    @Test
    fun onSignInFailed_network_mapsToFailedNetwork() {
        viewModel.onSignInFailed(FailureKind.Network)
        assertEquals(AuthUiState.Failed(FailureKind.Network), viewModel.uiState.value)
    }

    @Test
    fun onSignInFailed_unexpected_mapsToFailedUnexpected_theGenericExceptionCase() {
        viewModel.onSignInFailed(FailureKind.Unexpected)
        assertEquals(AuthUiState.Failed(FailureKind.Unexpected), viewModel.uiState.value)
    }

    @Test
    fun onSignInFailed_bridge_mapsToFailedBridge_theOnGoogleIdTokenCatchOutcome() {
        // Mirrors exactly what onGoogleIdToken's catch(e: Exception) branch does
        // (`_uiState.value = AuthUiState.Failed(FailureKind.Bridge)`) - see the
        // class doc for why the try/catch itself isn't driven directly here.
        viewModel.onSignInFailed(FailureKind.Bridge)
        assertEquals(AuthUiState.Failed(FailureKind.Bridge), viewModel.uiState.value)
    }

    @Test
    fun loggedIn_withNoPriorSignIn_isFalse() {
        assertEquals(false, viewModel.loggedIn.value)
    }
}
