package com.github.jayteealao.playster.ui.editorial

import com.github.jayteealao.playster.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The palette gate's pure legs: the palette→window-style mapping every splash
 * sync resolves through, and the synchronous write path's persistence +
 * normalization. The splash-theme *persistence* itself is platform-owned
 * (`SplashScreen.setSplashScreenTheme`) and is proven by the cold-start
 * frame-forensics drive at verify, not shadowed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorialThemeGateTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun assertThemeRes(
        expected: Int,
        paletteKey: String,
    ) = assertEquals(expected, EditorialThemeGate.themeResFor(paletteKey))

    @Test
    fun themeResFor_mapsEveryPaletteToItsWindowStyle() {
        assertThemeRes(R.style.Theme_Playster_Editorial_Cream, EditorialPalettes.Cream.key)
        assertThemeRes(R.style.Theme_Playster_Editorial_Vellum, EditorialPalettes.Vellum.key)
        assertThemeRes(R.style.Theme_Playster_Editorial_Newsprint, EditorialPalettes.Newsprint.key)
        assertThemeRes(R.style.Theme_Playster_Editorial_Night, EditorialPalettes.Night.key)
    }

    @Test
    fun themeResFor_unknownKeyFallsBackToCream() {
        assertEquals(R.style.Theme_Playster_Editorial_Cream, EditorialThemeGate.themeResFor("not-a-paper"))
    }

    @Test
    fun writePalette_persistsSynchronouslyForImmediateReadBack() {
        EditorialThemeGate.writePalette(context, EditorialPalettes.Night.key)
        assertEquals(EditorialPalettes.Night.key, EditorialThemeGate.savedPaletteKey(context))
    }

    @Test
    fun writePalette_normalizesUnknownKeysToCream() {
        EditorialThemeGate.writePalette(context, "not-a-paper")
        assertEquals(EditorialPalettes.Cream.key, EditorialThemeGate.savedPaletteKey(context))
    }

    @Test
    fun savedPalette_returnsTheTokenObjectForTheSavedKey() {
        EditorialThemeGate.writePalette(context, EditorialPalettes.Vellum.key)
        assertEquals(EditorialPalettes.Vellum, EditorialThemeGate.savedPalette(context))
    }
}
