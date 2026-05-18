package com.github.jayteealao.playster.screens.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AuthViewModel"

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authBridge: FirebaseAuthBridge,
) : ViewModel() {

    /** Surface for AuthScreen to render an error after a failed sign-in attempt. */
    var lastError: Exception? = null
        private set

    /**
     * Driven by Firebase Auth state — `true` once `signInWithCredential` resolves
     * and the AuthStateListener fires, `false` after sign-out.
     */
    val loggedIn: StateFlow<Boolean> = authBridge.currentUid
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = authBridge.currentUid.value != null,
        )

    /**
     * Exchange a Google Sign-In ID token for a Firebase Auth session. Returns
     * the Firebase uid on success; sets `lastError` and returns null on failure.
     */
    fun bridgeToFirebase(idToken: String, onResult: (uid: String?) -> Unit) {
        viewModelScope.launch {
            try {
                val uid = authBridge.signInWithGoogleIdToken(idToken)
                lastError = null
                Log.d(TAG, "Bridged to Firebase Auth uid=$uid")
                onResult(uid)
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "bridgeToFirebase failed", e)
                onResult(null)
            }
        }
    }

    fun saveLoginFailure(exception: Exception?) {
        lastError = exception
    }
}
