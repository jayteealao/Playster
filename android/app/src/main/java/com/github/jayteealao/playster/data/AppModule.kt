package com.github.jayteealao.playster.data

import android.content.Context
import androidx.credentials.CredentialManager
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.screens.player.playback.ProgressWriteSink
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // FirebaseEmulatorGate is a debug-only emulator redirect (release variant
    // is a compiled no-op); it must run before the first use of each SDK
    // singleton, hence inside the providers.

    @Provides
    @Singleton
    fun provideFirebaseAuth(
        @ApplicationContext context: Context,
    ): FirebaseAuth {
        FirebaseEmulatorGate.configure(context)
        return Firebase.auth
    }

    @Provides
    @Singleton
    fun provideFirebaseFirestore(
        @ApplicationContext context: Context,
    ): FirebaseFirestore {
        FirebaseEmulatorGate.configure(context)
        return Firebase.firestore
    }

    @Provides
    @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions = Firebase.functions

    /**
     * SEC-1: sign-out needs to clear the on-device Credential Manager state
     * (`clearCredentialState`) alongside the Firebase Auth session, or the
     * next "Continue with Google" tap silently auto-reselects the previous
     * account (`AuthScreen`'s returning-user path is `filterByAuthorizedAccounts
     * = true` + `setAutoSelectEnabled = true`). `CredentialManager.create()`
     * only needs a `Context` to launch UI on the *request*-credential path —
     * `clearCredentialState()` itself takes no context — so an application
     * context is safe here and lets [com.github.jayteealao.playster.data.auth.FirebaseAuthBridge]
     * stay a plain `@Singleton` with no Activity dependency.
     */
    @Provides
    @Singleton
    fun provideCredentialManager(
        @ApplicationContext context: Context,
    ): CredentialManager = CredentialManager.create(context)

    /**
     * BC-1: [com.github.jayteealao.playster.screens.player.playback.PlaybackSession]
     * needs only the one write [ProgressRepository] exposes — bind the narrow
     * [ProgressWriteSink] SAM to it here rather than injecting the whole
     * repository (see [ProgressWriteSink]'s KDoc for why).
     */
    @Provides
    fun provideProgressWriteSink(repository: ProgressRepository): ProgressWriteSink {
        return ProgressWriteSink(repository::upsertVideoProgress)
    }
}
