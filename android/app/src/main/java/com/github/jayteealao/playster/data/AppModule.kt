package com.github.jayteealao.playster.data

import android.content.Context
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
}
