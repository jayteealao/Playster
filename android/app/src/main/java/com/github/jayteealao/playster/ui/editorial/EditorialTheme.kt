package com.github.jayteealao.playster.ui.editorial

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * The editorial theme root. Tokens resolve in a [remember] keyed on every
 * axis, behind a [staticCompositionLocalOf] — so a live palette switch swaps
 * one immutable object instead of invalidating every reader, and everything
 * else recomposes only where tokens are actually read.
 *
 * A thin [MaterialTheme] bridge sits INSIDE the provider strictly for
 * Material component defaults (dialogs, ripples, third-party components):
 * background/surface = paper, onBackground/onSurface = ink, primary = accent,
 * dark scheme when the palette is Night. Editorial surfaces never read
 * Material color roles directly — they read [LocalEditorialTokens].
 */
@Composable
fun EditorialTheme(
    palette: PaperPalette = EditorialPalettes.Cream,
    accent: EditorialAccent = EditorialAccents.Oxblood,
    face: EditorialFace = EditorialFaces.Source,
    sizeStep: SizeStep = SizeStep.M,
    lineHeightStep: LineHeightStep = LineHeightStep.COMFORTABLE,
    density: DensityStep = DensityStep.DEFAULT,
    content: @Composable () -> Unit,
) {
    val tokens =
        remember(palette, accent, face, sizeStep, lineHeightStep, density) {
            EditorialTokens(
                palette = palette,
                accent = accent,
                face = face,
                sans = EditorialFonts.InterTight,
                sizeStep = sizeStep,
                lineHeightStep = lineHeightStep,
                density = density,
                type = editorialTypeRamp(face, sizeStep, lineHeightStep),
            )
        }
    val colorScheme =
        remember(palette, accent) {
            if (palette.dark) {
                darkColorScheme(
                    primary = accent.color,
                    onPrimary = palette.paper,
                    background = palette.paper,
                    onBackground = palette.ink,
                    surface = palette.paper,
                    onSurface = palette.ink,
                    surfaceVariant = palette.paperDeep,
                    onSurfaceVariant = palette.inkSoft,
                )
            } else {
                lightColorScheme(
                    primary = accent.color,
                    onPrimary = palette.paper,
                    background = palette.paper,
                    onBackground = palette.ink,
                    surface = palette.paper,
                    onSurface = palette.ink,
                    surfaceVariant = palette.paperDeep,
                    onSurfaceVariant = palette.inkSoft,
                )
            }
        }
    CompositionLocalProvider(LocalEditorialTokens provides tokens) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
