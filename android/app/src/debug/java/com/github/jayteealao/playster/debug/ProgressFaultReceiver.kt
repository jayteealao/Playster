package com.github.jayteealao.playster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.jayteealao.playster.data.ProgressFaultGate

/**
 * Debug-only writer for the progress fault-injection flag, so a verification
 * drive can force the next progress write to fail (and fire the
 * `progress_sync_write_failed` emit path) without editing code.
 *
 * Exists only in debug builds (src/debug); never ships in a release APK/AAB.
 *
 * One-shot: the repository clears the flag as it consumes it, so arm once →
 * exactly one forced failure → subsequent writes succeed.
 *
 * Trigger from a connected shell:
 *   adb shell am broadcast \
 *     -n com.github.jayteealao.playster/.debug.ProgressFaultReceiver \
 *     -a com.github.jayteealao.playster.debug.SET_PROGRESS_FAULT \
 *     --ez enabled true
 */
class ProgressFaultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        ProgressFaultGate.writeArmed(context, enabled)
        Log.i(TAG, "progressFault{armed=$enabled}")
    }

    private companion object {
        private const val TAG = "playster.debug"
        private const val EXTRA_ENABLED = "enabled"
    }
}
