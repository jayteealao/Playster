package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.edScaled

/**
 * The floating mini-player pill: an ink capsule with a play/pause glyph,
 * an ellipsized italic-serif title, and a tabular position readout at 0.8
 * alpha. Placement (the mock floats it 16dp above the transcript's bottom
 * edge) belongs to the consuming screen; playback behavior wires at the
 * transcript slice — this is the visual surface plus slots.
 *
 * The soft drop shadow is the mock's own (`0 6px 18px rgba(0,0,0,.18)`) —
 * the design brief bans in-screen card elevation, and this single floating
 * pill is the prototype's deliberate exception, kept for mock fidelity and
 * enumerated for the verify exception list.
 */
@Composable
fun MiniPlayerPill(
    title: String,
    position: String,
    playing: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    val titleStyle =
        TextStyle(
            fontFamily = tokens.face.display,
            fontStyle = FontStyle.Italic,
            fontSize = edScaled(PILL_TITLE_SP, tokens.sizeStep.multiplier).sp,
        )
    val positionStyle =
        TextStyle(
            fontFamily = tokens.sans,
            fontSize = edScaled(PILL_POSITION_SP, tokens.sizeStep.multiplier).sp,
            letterSpacing = 0.3.sp,
            fontFeatureSettings = TABULAR_NUMS,
        )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = SHADOW_ALPHA),
                    spotColor = Color.Black.copy(alpha = SHADOW_ALPHA),
                )
                .clip(CircleShape)
                .background(tokens.palette.ink)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.clickable(role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) EditorialIcons.Pause else EditorialIcons.Play,
                contentDescription = if (playing) "Pause" else "Play",
                tint = tokens.palette.paper,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = title,
            style = titleStyle,
            color = tokens.palette.paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = position,
            style = positionStyle,
            color = tokens.palette.paper,
            modifier = Modifier.alpha(POSITION_ALPHA),
        )
    }
}

private const val PILL_TITLE_SP = 12.0
private const val PILL_POSITION_SP = 10.0
private const val POSITION_ALPHA = 0.8f
private const val SHADOW_ALPHA = 0.18f
