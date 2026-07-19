package com.github.jayteealao.playster.data

import android.content.Context

/**
 * Debug-only fault-injection seam for the progress write path, behind a
 * synchronous debug-only preference (written by
 * [com.github.jayteealao.playster.debug.ProgressFaultReceiver]).
 *
 * The app's write side conforms to the deployed Firestore rules, so a
 * rules-denied write can never be forced on a running build — which left the
 * `progress_sync_write_failed` instrumentation signal (04b §player)
 * code-proven only. Arming this gate makes the NEXT progress write throw
 * inside the repository's existing try/catch, so the *real* emit path fires
 * (logcat `writeFailed{...}` + Crashlytics keys) under a live drive.
 *
 * One-shot by design: [consumeArmed] clears the flag as it reads it, so a
 * single armed drive produces exactly one forced failure and every
 * subsequent write behaves normally — the drive can also prove recovery.
 *
 * The release source set compiles a no-op twin of this object: fault
 * injection is structurally unreachable in shipped builds.
 */
object ProgressFaultGate {
    private const val PREFS_NAME = "debug_progress_fault"
    private const val KEY_FAIL_NEXT_WRITE = "fail_next_write"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Synchronous write (`commit`, like the emulator gate): the flag must be
     * durably on disk regardless of what the broadcast-delivery process does
     * next.
     */
    fun writeArmed(
        context: Context,
        armed: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_FAIL_NEXT_WRITE, armed).commit()
    }

    /** One-shot read-and-clear: true exactly once per arming. */
    fun consumeArmed(context: Context): Boolean {
        val armed = prefs(context).getBoolean(KEY_FAIL_NEXT_WRITE, false)
        if (armed) prefs(context).edit().putBoolean(KEY_FAIL_NEXT_WRITE, false).commit()
        return armed
    }
}
