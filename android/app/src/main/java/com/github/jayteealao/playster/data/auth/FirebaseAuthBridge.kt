package com.github.jayteealao.playster.data.auth

import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.crashlytics.crashlytics
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "playster.auth"

/**
 * Bridges a Google Sign-In ID token into a Firebase Auth session. Exposes the
 * current uid as a `StateFlow` so view models can react to sign-in / sign-out
 * without holding a `FirebaseAuth` reference.
 */
@Singleton
class FirebaseAuthBridge
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val credentialManager: CredentialManager,
    ) {
        private val _currentUid = MutableStateFlow(firebaseAuth.currentUser?.uid)
        val currentUid: StateFlow<String?> = _currentUid.asStateFlow()

        /** The signed-in user's Google display name, for the Settings masthead. */
        val currentDisplayName: String?
            get() = firebaseAuth.currentUser?.displayName

        /** When this account was created (epoch millis) — the "subscriber since" line. */
        val accountCreatedAtMillis: Long?
            get() = firebaseAuth.currentUser?.metadata?.creationTimestamp

        private val authStateListener =
            FirebaseAuth.AuthStateListener { auth ->
                val uid = auth.currentUser?.uid
                _currentUid.value = uid
                // Instrumentation (04b-instrument §5): attribute crash reports to the
                // signed-in user via Crashlytics only — never the uid in logcat or a
                // custom key (PII rule). Covers fresh sign-in, restored sessions, and
                // sign-out (cleared to empty).
                Firebase.crashlytics.setUserId(uid ?: "")
            }

        init {
            firebaseAuth.addAuthStateListener(authStateListener)
        }

        /**
         * Exchange a Google Sign-In ID token for a Firebase Auth session.
         * Returns the resulting Firebase uid.
         */
        suspend fun signInWithGoogleIdToken(idToken: String): String {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            val uid =
                result.user?.uid
                    ?: error("signInWithCredential returned no Firebase user")
            return uid
        }

        /**
         * SEC-1: end the Firebase Auth session, then best-effort forget the
         * on-device Credential Manager authorization for this app. A
         * `clearCredentialState` failure (e.g. no provider configured) must
         * never block sign-out itself — the Firebase session is already torn
         * down by the time we attempt it — so it's caught and logged, not
         * propagated.
         */
        suspend fun signOut() {
            firebaseAuth.signOut()
            runCatching {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }.onFailure { e -> Log.w(TAG, "clearCredentialState failed", e) }
        }

        /** Cold flow form of the auth state, for non-injected consumers. */
        fun authStateFlow(): Flow<String?> =
            callbackFlow {
                val listener =
                    FirebaseAuth.AuthStateListener { auth ->
                        trySend(auth.currentUser?.uid)
                    }
                firebaseAuth.addAuthStateListener(listener)
                awaitClose { firebaseAuth.removeAuthStateListener(listener) }
            }
    }
