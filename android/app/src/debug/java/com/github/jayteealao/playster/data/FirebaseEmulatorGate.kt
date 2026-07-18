package com.github.jayteealao.playster.data

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only Firebase emulator wiring, behind a synchronous debug-only
 * preference (written by [com.github.jayteealao.playster.debug.EmulatorPrefReceiver]).
 * When the flag is set, the Auth, Firestore, and Functions singletons are
 * pointed at the host machine's local emulator suite exactly once, before Hilt
 * hands them out — [configure] is called by the AppModule providers and by the
 * debug sign-in receiver, whichever runs first in the process. Functions must be
 * wired alongside Auth/Firestore, otherwise callables like `requestVideoSummary`
 * hit production and fail UNAUTHENTICATED against the emulator's fixture user.
 *
 * Ordering contract: `useEmulator` must run before the first use of each
 * SDK singleton, so the flag only takes effect on the NEXT process start —
 * enable it, then force-stop, then drive. The verification flows encode
 * that order.
 *
 * The release source set compiles a no-op twin of this object: emulator
 * wiring is structurally unreachable in shipped builds.
 */
object FirebaseEmulatorGate {
    private const val TAG = "playster.debug"
    private const val PREFS_NAME = "debug_firebase_emulator"
    private const val KEY_ENABLED = "use_emulator"
    private const val EMULATOR_HOST = "10.0.2.2"
    private const val AUTH_PORT = 9099
    private const val FIRESTORE_PORT = 8080
    private const val FUNCTIONS_PORT = 5001

    private val configured = AtomicBoolean(false)

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /**
     * Synchronous write (`commit`, like the theme gate): the flag must be
     * durably on disk before the force-stop that precedes the next drive.
     */
    fun writeEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
    }

    /** Points Auth + Firestore + Functions at the local emulators, once, if enabled. */
    fun configure(context: Context) {
        if (!isEnabled(context)) return
        if (!configured.compareAndSet(false, true)) return
        Firebase.auth.useEmulator(EMULATOR_HOST, AUTH_PORT)
        Firebase.firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        Firebase.functions.useEmulator(EMULATOR_HOST, FUNCTIONS_PORT)
        Log.i(
            TAG,
            "firebaseEmulator{auth=$EMULATOR_HOST:$AUTH_PORT," +
                "firestore=$EMULATOR_HOST:$FIRESTORE_PORT," +
                "functions=$EMULATOR_HOST:$FUNCTIONS_PORT}",
        )
    }
}
