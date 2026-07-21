package com.github.jayteealao.playster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.jayteealao.playster.ui.editorial.EditorialThemeGate

/**
 * Debug-only palette pref writer, so cold-start recordings can seed a saved
 * palette without any Settings UI existing yet.
 *
 * Exists only in debug builds (src/debug); never ships in a release APK/AAB.
 *
 * Trigger from a connected shell:
 *   adb shell am broadcast \
 *     -n com.github.jayteealao.playster/.debug.ThemePrefReceiver \
 *     -a com.github.jayteealao.playster.debug.SET_PALETTE \
 *     --es palette night
 */
class ThemePrefReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val requested = intent.getStringExtra(EXTRA_PALETTE)
        EditorialThemeGate.writePalette(context, requested ?: "")
        val written = EditorialThemeGate.savedPaletteKey(context)
        Log.i(TAG, "setPalette{requested=$requested,written=$written}")
    }

    private companion object {
        private const val TAG = "playster.debug"
        private const val EXTRA_PALETTE = "palette"
    }
}
