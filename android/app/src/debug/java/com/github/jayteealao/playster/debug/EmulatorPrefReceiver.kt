package com.github.jayteealao.playster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.jayteealao.playster.data.FirebaseEmulatorGate

/**
 * Debug-only writer for the Firebase-emulator flag, so verification drives
 * can point Auth + Firestore at the host machine's local emulator suite
 * without any UI existing for it.
 *
 * Exists only in debug builds (src/debug); never ships in a release APK/AAB.
 *
 * The flag takes effect on the NEXT process start (SDK singletons must be
 * pointed at the emulator before first use) — force-stop the app after
 * enabling.
 *
 * Trigger from a connected shell:
 *   adb shell am broadcast \
 *     -n com.github.jayteealao.playster/.debug.EmulatorPrefReceiver \
 *     -a com.github.jayteealao.playster.debug.SET_FIREBASE_EMULATOR \
 *     --ez enabled true
 */
class EmulatorPrefReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        FirebaseEmulatorGate.writeEnabled(context, enabled)
        Log.i(TAG, "setFirebaseEmulator{enabled=$enabled}")
    }

    private companion object {
        private const val TAG = "playster.debug"
        private const val EXTRA_ENABLED = "enabled"
    }
}
