package com.github.jayteealao.playster.ui.editorial

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StyleRes
import com.github.jayteealao.playster.R

/**
 * The flash-free cold-start gate.
 *
 * The saved palette lives in its own synchronous [android.content.SharedPreferences]
 * file — deliberately NOT the app's async DataStore — because the whole point
 * is to know the paper tone *before* the first frame. [applyPreSetContent]
 * maps the saved palette to a window style whose `windowBackground` is that
 * palette's paper, so even the pre-Compose window is already the right color.
 *
 * Call order inside an editorial activity:
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     EditorialThemeGate.applyPreSetContent(this) // before super.onCreate
 *     super.onCreate(savedInstanceState)
 *     val palette = EditorialThemeGate.savedPalette(this)
 *     setContent { EditorialTheme(palette = palette) { ... } }
 * }
 * ```
 * The Settings screen (a later change) writes the same preference via
 * [writePalette]; face/size/line-height live in the async DataStore because
 * nothing needs them before composition.
 */
object EditorialThemeGate {
    private const val PREFS_NAME = "editorial_theme"
    private const val KEY_PALETTE = "palette"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Synchronous read of the saved palette key; unknown/absent → cream. */
    fun savedPaletteKey(context: Context): String {
        val saved = prefs(context).getString(KEY_PALETTE, null)
        return EditorialPalettes.fromKey(saved).key
    }

    /** The saved palette's token object, for the composable layer. */
    fun savedPalette(context: Context): PaperPalette = EditorialPalettes.fromKey(savedPaletteKey(context))

    /**
     * Applies the saved palette's window style. MUST run before
     * `super.onCreate`/`setContent` — that is what makes the pre-first-frame
     * window paint in the saved paper instead of the manifest default.
     */
    fun applyPreSetContent(activity: Activity) {
        activity.setTheme(themeResFor(savedPaletteKey(activity)))
    }

    /**
     * Keeps the OS starting window (the system splash, API 31+) on the saved
     * paper for *future* cold starts. The current launch's splash is inflated
     * from the manifest theme before the process exists — no runtime call can
     * repaint it — but `SplashScreen.setSplashScreenTheme` "overrides and
     * persists the theme used for the SplashScreen of this application"
     * (source: Sdk/sources/android-34/android/window/SplashScreen.java), so
     * one sync per launch makes every subsequent cold start splash in the
     * saved palette.
     *
     * Two platform caveats the call site must respect:
     * - The platform persists the override by *resolved theme name* — the
     *   `Theme.Playster.Editorial.*` style names must stay stable across
     *   releases or persisted overrides dangle.
     * - A palette written while no activity runs (e.g. the debug pref
     *   receiver) is picked up on the next launch's sync — one launch of lag,
     *   self-healing because this runs on every `onCreate`. Palette changes
     *   made while the app runs have no lag: MainActivity's palette collector
     *   re-runs this sync on every change, so the very next cold start
     *   already splashes in the new paper.
     *
     * No-op below API 31: the system splash does not exist there and the
     * editorial window styles disable the legacy starting-window preview.
     */
    fun syncSplashTheme(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.splashScreen.setSplashScreenTheme(themeResFor(savedPaletteKey(activity)))
        }
    }

    @StyleRes
    fun themeResFor(paletteKey: String): Int =
        when (EditorialPalettes.fromKey(paletteKey).key) {
            EditorialPalettes.Vellum.key -> R.style.Theme_Playster_Editorial_Vellum
            EditorialPalettes.Newsprint.key -> R.style.Theme_Playster_Editorial_Newsprint
            EditorialPalettes.Night.key -> R.style.Theme_Playster_Editorial_Night
            else -> R.style.Theme_Playster_Editorial_Cream
        }

    /**
     * Synchronous palette write. `commit()` (not `apply()`) on purpose: the
     * value must be durably on disk before the next cold start reads it —
     * an async write racing a force-stop is exactly the flash bug this gate
     * exists to prevent. One tiny string pref; the blocking cost is trivial.
     */
    fun writePalette(
        context: Context,
        paletteKey: String,
    ) {
        val normalized = EditorialPalettes.fromKey(paletteKey).key
        prefs(context).edit().putString(KEY_PALETTE, normalized).commit()
    }
}
