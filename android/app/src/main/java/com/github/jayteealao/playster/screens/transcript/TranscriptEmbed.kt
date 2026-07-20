package com.github.jayteealao.playster.screens.transcript

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.github.jayteealao.playster.screens.player.playback.PlaybackState
import com.github.jayteealao.playster.screens.player.playback.YouTubePlayerHost
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule

/**
 * The Transcript's persistent visible mini-embed — the derived surface Option 1
 * adds so playback stays *visible* while reading (RIM-7, C5). PO-pinned to
 * **Probe B "Masthead strip"** (po-answers.md 2026-07-19): a full-bleed 16:9
 * band pinned under the AppBar, the same family as the Player's PO-chosen
 * Masthead-band panel, so Player→Transcript reads as one continuous surface. The
 * article flows beneath the band; the floating [com.github.jayteealao.playster.ui.editorial.components.MiniPlayerPill]
 * stays the control strip.
 *
 * The embed is the shared [PlaybackSession]'s single retained view ([YouTubePlayerHost]),
 * so the transcript never instantiates a second player and must keep the official
 * YouTube embed continuously visible while playing (no background/audio-only, no
 * fake control bar). The band is a squared 16:9 surface with no rounding, no
 * scrim, no elevation; at the 412dp reference width it is ~232dp, clearing the
 * 200×200px YouTube RMF floor. The "▶ Playing/Paused" furniture sits in a thin
 * bar *beneath* the video rectangle, never over it (RMF: no chrome on the video
 * surface). The no-video / embed-disabled state drops the band for an editorial
 * error line so the transcript stays readable beneath.
 */
@Composable
fun TranscriptEmbed(
    session: PlaybackSession,
    playbackState: PlaybackState,
    positionLabel: String,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    val error = (playbackState as? PlaybackState.Error)?.error
    val furnitureStyle =
        TextStyle(
            fontFamily = tokens.sans,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.4.sp,
        )

    Column(modifier = modifier.fillMaxWidth().testTag("transcript-embed")) {
        if (error != null) {
            EditorialErrorNotice(
                message = error.editorialMessage,
                actionLabel = error.retryLabel ?: "Open the player",
                onAction = onOpenPlayer,
                modifier =
                    Modifier
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                        .testTag("transcript-embed-error"),
            )
        } else {
            val playing = playbackState is PlaybackState.Playing
            val loading = playbackState is PlaybackState.Loading
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().background(BAND_PLACEHOLDER)) {
                // Full 16:9 — clears the YouTube RMF 200px floor at the reference width.
                val bandHeight = maxWidth * PANEL_RATIO
                Box(
                    modifier = Modifier.fillMaxWidth().height(bandHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    YouTubePlayerHost(
                        session = session,
                        modifier = Modifier.fillMaxWidth().height(bandHeight),
                    )
                    if (loading) {
                        Text(
                            text = "Cueing the recording…",
                            style = furnitureStyle.copy(letterSpacing = 0.3.sp, fontStyle = FontStyle.Italic),
                            color = WARM_ON_BAND,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
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
                    color = if (playing) tokens.accent.color else WARM_ON_BAND,
                )
                Text(
                    text = positionLabel,
                    style = furnitureStyle.copy(letterSpacing = 0.3.sp, fontFeatureSettings = "tnum"),
                    color = WARM_ON_BAND,
                )
            }
        }
        EditorialRule()
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

private const val PANEL_RATIO = 9f / 16f
