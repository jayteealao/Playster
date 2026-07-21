package com.github.jayteealao.playster.screens.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AuthViewModel"

@HiltViewModel
class AuthViewModel
    @Inject
    constructor(
        private val authBridge: FirebaseAuthBridge,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)

        /** The cover page renders from this — idle, authenticating, or a specific failure. */
        val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

        /**
         * Driven by Firebase Auth state — `true` once `signInWithCredential` resolves
         * and the AuthStateListener fires, `false` after sign-out. The graph-level
         * session gate consumes this; its contract is unchanged.
         */
        val loggedIn: StateFlow<Boolean> =
            authBridge.currentUid
                .map { it != null }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = authBridge.currentUid.value != null,
                )

        /** The user tapped sign-in; the credential sheet is about to open. */
        fun onSignInStarted() {
            _uiState.value = AuthUiState.Authenticating
        }

        /** The credential sheet was dismissed — a choice, not an error. Back to idle. */
        fun onSignInCancelled() {
            _uiState.value = AuthUiState.Idle
        }

        /** A real sign-in failure before any token was obtained. */
        fun onSignInFailed(kind: FailureKind) {
            _uiState.value = AuthUiState.Failed(kind)
        }

        /**
         * Exchange a Google Sign-In ID token for a Firebase Auth session. On success
         * the state stays [AuthUiState.Authenticating] — the session gate navigates
         * away on the `loggedIn` flip, so the screen never flashes back through idle.
         * On failure it surfaces [FailureKind.Bridge].
         */
        fun onGoogleIdToken(idToken: String) {
            viewModelScope.launch {
                try {
                    authBridge.signInWithGoogleIdToken(idToken)
                    Log.d(TAG, "Bridged to Firebase Auth (session established)")
                    // Deliberately leave state as Authenticating: the loggedIn flip
                    // routes to Home before any further state emission.
                } catch (e: Exception) {
                    Log.e(TAG, "bridgeToFirebase failed", e)
                    _uiState.value = AuthUiState.Failed(FailureKind.Bridge)
                }
            }
        }
    }
