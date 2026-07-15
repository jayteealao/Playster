package com.github.jayteealao.playster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.jayteealao.playster.data.FirebaseEmulatorGate
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Debug-only fixture sign-in against the local Firebase Auth emulator, so
 * navigation flows can drive a signed-in session with no human-owned Google
 * account. Seed the fixture user first (maestro/helpers/seed-auth-emulator.sh)
 * and enable the emulator gate ([EmulatorPrefReceiver]) before the process
 * starts — this receiver configures the gate defensively, but a process that
 * already used Auth against production cannot be re-pointed.
 *
 * Exists only in debug builds (src/debug); never ships in a release APK/AAB.
 * The fixture credentials are valid ONLY against a local Auth emulator
 * (seeded by the helper script) — they are test fixtures, not secrets.
 *
 * Success/failure is logged on the `playster.debug` tag so flows can assert
 * `debugSignIn{status=success}` via lazylogcat.
 *
 * Trigger from a connected shell:
 *   adb shell am broadcast \
 *     -n com.github.jayteealao.playster/.debug.DebugAuthReceiver \
 *     -a com.github.jayteealao.playster.debug.SIGN_IN
 */
class DebugAuthReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        FirebaseEmulatorGate.configure(context)
        val email = intent.getStringExtra(EXTRA_EMAIL) ?: FIXTURE_EMAIL
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: FIXTURE_PASSWORD
        val pending = goAsync()
        Firebase.auth
            .signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                Log.i(TAG, "debugSignIn{status=success}")
                pending.finish()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "debugSignIn{status=failure}", e)
                pending.finish()
            }
    }

    private companion object {
        private const val TAG = "playster.debug"
        private const val EXTRA_EMAIL = "email"
        private const val EXTRA_PASSWORD = "password"

        /** Emulator-only fixture identity — see seed-auth-emulator.sh. */
        private const val FIXTURE_EMAIL = "verify@playster.test"
        private const val FIXTURE_PASSWORD = "playster-verify-fixture"
    }
}
