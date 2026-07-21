package com.github.jayteealao.playster.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.EditorialThemeGate

/**
 * Debug-only host for the token sample sheet, cold-startable from the shell:
 *
 *   adb shell am start -n com.github.jayteealao.playster/.debug.TokenSampleSheetActivity
 *
 * It exercises the PRODUCTION no-flash path end to end — synchronous palette
 * read + window-style application before setContent — so first-frame palette
 * correctness can be recorded before any product screen adopts the editorial
 * theme. The main app activity adopts the identical call order later.
 */
class TokenSampleSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        EditorialThemeGate.applyPreSetContent(this)
        // Mirror MainActivity's call order: persist the saved palette's splash
        // theme so the NEXT cold start's OS starting window is palette-true too
        // (the recording script's sync launch depends on this).
        EditorialThemeGate.syncSplashTheme(this)
        super.onCreate(savedInstanceState)
        val palette = EditorialThemeGate.savedPalette(this)
        setContent {
            EditorialTheme(palette = palette) {
                TokenSampleSheet()
            }
        }
    }
}
