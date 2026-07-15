package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.edScaled

/**
 * The recent-search pill: 1dp rule border, radius 999, italic serif body
 * at 11 in ink, the prototype's 5x10 padding. The visible chip is small by
 * design; the touch target extends invisibly to >=48dp.
 */
@Composable
fun EditorialChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    val style =
        TextStyle(
            fontFamily = tokens.face.body,
            fontStyle = FontStyle.Italic,
            fontSize = edScaled(CHIP_LABEL_SP, tokens.sizeStep.multiplier).sp,
        )
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .border(width = 1.dp, color = tokens.palette.rule, shape = CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = text, style = style, color = tokens.palette.ink)
    }
}

private const val CHIP_LABEL_SP = 11.0
