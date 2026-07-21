package com.github.jayteealao.playster.ui.editorial

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * Every token an editorial surface needs, resolved for one palette + accent +
 * face + step selection. Reached via [LocalEditorialTokens]; provided by
 * [EditorialTheme].
 */
@Immutable
data class EditorialTokens(
    val palette: PaperPalette,
    val accent: EditorialAccent,
    val face: EditorialFace,
    val sans: FontFamily,
    val sizeStep: SizeStep,
    val lineHeightStep: LineHeightStep,
    val density: DensityStep,
    val type: EditorialTypeRamp,
)

val LocalEditorialTokens =
    staticCompositionLocalOf<EditorialTokens> {
        error("No EditorialTokens provided — wrap this composable in EditorialTheme { }")
    }
