package com.github.jayteealao.playster.screens.transcript

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.TABULAR_NUMS
import com.github.jayteealao.playster.ui.editorial.edScaled

/**
 * One transcript paragraph as the mock draws it: a 36dp timestamp gutter (accent
 * + w700 when this is the active line, else inkFaint w500) beside the serif body
 * (13.5/1.55 — active ink w500, highlighted ink over the `highlight` token span,
 * else inkSoft), with an optional accent-ruled italic marginalia note beneath.
 *
 * Tap seeks playback to this line ([onSeek], AC1); a long-press toggles the
 * highlight ([onToggleHighlight], AC2). The whole row is the touch target, well
 * past the 48dp floor with its padding. [active] is fed by the caller's
 * `derivedStateOf`, so the per-second position tick only recomposes the two rows
 * whose active-ness flips — never the list (Step 9).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptRow(
    paragraph: TranscriptParagraph,
    active: Boolean,
    onSeek: () -> Unit,
    onToggleHighlight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = onSeek,
                    onLongClick = onToggleHighlight,
                )
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = paragraph.timestampLabel,
            style =
                TextStyle(
                    fontFamily = tokens.sans,
                    fontSize = edScaled(GUTTER_SP, tokens.sizeStep.multiplier).sp,
                    letterSpacing = 0.3.sp,
                    fontFeatureSettings = TABULAR_NUMS,
                    fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
                ),
            color = if (active) tokens.accent.color else tokens.palette.inkFaint,
            modifier = Modifier.width(36.dp).padding(top = 3.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            val bodyStyle =
                tokens.type.body.copy(
                    fontSize = edScaled(BODY_SP, tokens.sizeStep.multiplier).sp,
                    fontWeight = if (active) FontWeight.W500 else FontWeight.W400,
                )
            val bodyColor = if (paragraph.highlighted || active) tokens.palette.ink else tokens.palette.inkSoft
            Text(
                text = paragraph.text,
                style = bodyStyle,
                color = bodyColor,
                modifier =
                    if (paragraph.highlighted) {
                        Modifier.background(tokens.palette.highlight).padding(horizontal = 2.dp)
                    } else {
                        Modifier
                    },
            )
            paragraph.note?.let { note ->
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier =
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(tokens.accent.color),
                    )
                    Text(
                        text = note,
                        style =
                            tokens.type.deck.copy(
                                fontSize = edScaled(MARGINALIA_SP, tokens.sizeStep.multiplier).sp,
                                fontStyle = FontStyle.Italic,
                            ),
                        color = tokens.palette.inkSoft,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

private const val GUTTER_SP = 9.5
private const val BODY_SP = 13.5
private const val MARGINALIA_SP = 12.0
