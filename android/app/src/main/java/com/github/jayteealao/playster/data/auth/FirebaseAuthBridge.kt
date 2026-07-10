package com.github.jayteealao.playster.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

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
    ) {
        private val _currentUid = MutableStateFlow(firebaseAuth.currentUser?.uid)
        val currentUid: StateFlow<String?> = _currentUid.asStateFlow()

        private val authStateListener =
            FirebaseAuth.AuthStateListener { auth ->
                _currentUid.value = auth.currentUser?.uid
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

        suspend fun signOut() {
            firebaseAuth.signOut()
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
