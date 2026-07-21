package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.EditorialTokens
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.edScaled

/*
 * The design's button idiom — no Material filled buttons anywhere in the
 * editorial surface. Two shapes: the ink pill (the Continue button) and the
 * borderless inline text action (the Download idiom).
 */

/**
 * Ink-filled pill: 1.5dp ink border, paper content, radius 999, the
 * prototype's 7x14 padding and 6dp icon gap. The touch target expands
 * invisibly to the platform 48dp minimum (clickable-node touch-bounds
 * expansion — layout size stays mock-exact); the disabled treatment
 * (0.45 alpha) is this port's addition — the mock draws none.
 */
@Composable
fun EditorialPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier =
            modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(CircleShape)
                .background(tokens.palette.ink)
                .border(width = 1.5.dp, color = tokens.palette.ink, shape = CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.palette.paper,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(text = text, style = tokens.actionLabelStyle(), color = tokens.palette.paper)
    }
}

/**
 * Borderless inline action: optional inkSoft icon + sans-11 ink label
 * (the mock's Download affordance). Same invisible >=48dp touch extension.
 */
@Composable
fun EditorialTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier =
            modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.palette.inkSoft,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(text = text, style = tokens.actionLabelStyle(weight = FontWeight.W400), color = tokens.palette.ink)
    }
}

private const val DISABLED_ALPHA = 0.45f
private const val ACTION_LABEL_SP = 11.0

/** Sans action label — 11sp, ls 0.2, W600 on the pill per the mock. */
internal fun EditorialTokens.actionLabelStyle(weight: FontWeight = FontWeight.W600): TextStyle =
    TextStyle(
        fontFamily = sans,
        fontSize = edScaled(ACTION_LABEL_SP, sizeStep.multiplier).sp,
        fontWeight = weight,
        letterSpacing = 0.2.sp,
    )
