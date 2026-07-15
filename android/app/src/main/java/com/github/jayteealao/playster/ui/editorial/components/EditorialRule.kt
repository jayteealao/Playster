package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * The hairline rule — the system's entire structural voice (no cards, no
 * elevation, no filled containers). 1dp at the palette's `rule` by default;
 * pass `ruleFaint` for the whisper weight or the accent color for accent
 * marks. Per the visual contract's finish rules, anything thicker than 1dp
 * (1.5–2dp) is reserved for accent marks — never for structure.
 */
@Composable
fun EditorialRule(
    modifier: Modifier = Modifier,
    color: Color = LocalEditorialTokens.current.palette.rule,
    thickness: Dp = 1.dp,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(thickness)
                .background(color),
    )
}
