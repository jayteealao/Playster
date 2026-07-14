package com.github.jayteealao.playster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Debug-only crash trigger for smoke-testing crash reporting delivery.
 *
 * Exists only in debug builds (src/debug); never ships in a release APK/AAB.
 * Throwing on the main thread reproduces the in-process uncaught-exception
 * path that Crashlytics documents for delivery smoke tests (an `am crash`
 * from the shell kills the process from the system side instead and never
 * exercises the reporter).
 *
 * Trigger from a connected shell:
 *   adb shell am broadcast \
 *     -n com.github.jayteealao.playster/.debug.CrashSmokeReceiver \
 *     -a com.github.jayteealao.playster.DEBUG_CRASH
 */
class CrashSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.w(TAG, "crashSmoke{action=${intent.action}} - throwing test crash on main thread")
        Handler(Looper.getMainLooper()).post {
            throw RuntimeException("crashlytics-smoke-test")
        }
    }

    private companion object {
        private const val TAG = "playster.debug"
    }
}
