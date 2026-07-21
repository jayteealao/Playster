package com.github.jayteealao.playster.screens.player

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * The collapsible video panel — the workflow's one derived surface, PO-pinned to
 * Probe B "Masthead band" (po-answers.md 2026-07-15T20:22Z). A full-bleed,
 * edge-to-edge 16:9 band at the very top of the screen (the masthead); the
 * article header sits below it (the standfirst). At the 412dp reference width
 * the band is ~232dp — clearing the 200×200px YouTube RMF floor. It retracts
 * upward into a thin edge-to-edge strip that still shows the live embed (never a
 * fake now-playing bar), over a 250ms ease-out height animation with no bounce.
 *
 * ToS/RMF discipline (AC2): the embed is *never obscured* — the panel furniture
 * (the "▶ Playing" indicator and the collapse/expand control) lives in a thin
 * bar directly beneath the band, not painted over the playing video surface. A
 * true 16:9 band has no letterbox margin to place chrome in, so the contract's
 * "only in the letterbox margin — no chrome on the video surface" rule resolves
 * to furniture *outside* the video rectangle. The [player] view is a single
 * remembered instance that is only clipped as the band height animates, so the
 * WebView is never detached across the transition.
 *
 * Composed only from tokens/existing type — the placeholder band color is a warm
 * near-black (never pure #000), painted only until the WebView renders.
 */
@Composable
fun VideoPanel(
    expanded: Boolean,
    playing: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
    player: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    val onBand = WARM_ON_BAND
    val furnitureStyle =
        TextStyle(
            fontFamily = tokens.sans,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.4.sp,
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(BAND_PLACEHOLDER)
                .testTag("player-video-panel"),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val expandedHeight = maxWidth * PANEL_RATIO
            // Read as a State<Dp> (not `by`-delegated) so the animated value is
            // consumed inside the layout phase below, not the composition phase —
            // an animation frame then invalidates only this node's layout, not the
            // whole VideoPanel composition (see MOT-1).
            val bandHeightState =
                animateDpAsState(
                    targetValue = if (expanded) expandedHeight else COLLAPSED_HEIGHT,
                    animationSpec = tween(PANEL_MOTION_MS, easing = EaseOut),
                    label = "panel-height",
                )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val heightPx = bandHeightState.value.roundToPx()
                            val placeable =
                                measurable.measure(constraints.copy(minHeight = 0, maxHeight = heightPx))
                            layout(placeable.width, heightPx) { placeable.placeRelative(0, 0) }
                        }
                        .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                // The embed keeps its full 16:9 size; the band only clips it, so
                // the WebView is never re-laid-out across collapse/expand.
                player(Modifier.fillMaxWidth().height(expandedHeight))
                if (loading) {
                    Text(
                        text = "Cueing the recording…",
                        style = furnitureStyle.copy(letterSpacing = 0.3.sp, fontStyle = FontStyle.Italic),
                        color = onBand,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Furniture bar — beneath the video rectangle, never over it (RMF).
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(BAND_PLACEHOLDER)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playing) "▶ PLAYING" else "PAUSED",
                style = furnitureStyle,
                color = if (playing) tokens.accent.color else onBand,
            )
            Text(
                text = if (expanded) "COLLAPSE ▲" else "EXPAND ▼",
                style = furnitureStyle,
                color = onBand.copy(alpha = CONTROL_ALPHA),
                modifier =
                    Modifier
                        .clickable(role = Role.Button, onClick = onToggle)
                        .padding(4.dp)
                        .testTag("player-panel-collapse"),
            )
        }
    }
}

/**
 * Warm near-black placeholder until the WebView paints (never pure #000). The
 * video band intentionally pins Night's paper regardless of the active
 * palette, so this reads Night directly rather than the ambient tokens.
 */
private val BAND_PLACEHOLDER = EditorialPalettes.Night.paperDeep

/**
 * Warm off-white for on-band furniture (never pure #FFF). Pinned to Night's
 * ink for the same reason as [BAND_PLACEHOLDER].
 */
private val WARM_ON_BAND = EditorialPalettes.Night.ink

private const val CONTROL_ALPHA = 0.7f
private const val PANEL_RATIO = 9f / 16f
private val COLLAPSED_HEIGHT = 64.dp
private const val PANEL_MOTION_MS = 250
