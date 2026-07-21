package com.github.jayteealao.playster.ui.editorial.chrome

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.github.jayteealao.playster.ui.editorial.PaperPalette

/**
 * Palette-aware edge-to-edge system chrome: both bars go transparent so the
 * paper field runs under them, and icon contrast follows the palette's
 * `dark` flag — Night gets dark bars with light icons, the three light
 * papers get dark icons. Bars are always the palette's paper (by showing
 * it through), never a scrim gray.
 *
 * Called from `onCreate` after the theme gate has resolved the saved
 * palette. The palette is static per-process in this slice; the settings
 * screen re-applies on a live palette switch when it lands.
 */
fun applyEditorialSystemBars(
    activity: ComponentActivity,
    palette: PaperPalette,
) {
    val barStyle =
        if (palette.dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
    activity.enableEdgeToEdge(
        statusBarStyle = barStyle,
        navigationBarStyle = barStyle,
    )
}
