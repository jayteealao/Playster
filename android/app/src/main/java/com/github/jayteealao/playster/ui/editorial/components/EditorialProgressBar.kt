package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * The thin editorial progress mark: a 1dp rule-colored track with an ink
 * fill (the headliner idiom) or accent fill (the shelf idiom), and an
 * optional 5dp accent scrub dot centered on the playhead (the player
 * idiom). Always determinate — the editorial voice has no spinner.
 *
 * The primitive renders whatever fraction it is given; the design's
 * "hide below 2%" rule is deliberately the consumer's (a row simply omits
 * the bar), so list rows can also drop the reserved vertical space.
 */
@Composable
fun EditorialProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    fill: Color = LocalEditorialTokens.current.palette.ink,
    showScrubDot: Boolean = false,
) {
    val tokens = LocalEditorialTokens.current
    val track = tokens.palette.rule
    val accent = tokens.accent.color
    val fraction = progress.coerceIn(0f, 1f)
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (showScrubDot) DOT_SIZE else TRACK_THICKNESS)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
    ) {
        val trackPx = TRACK_THICKNESS.toPx()
        val trackTop = (size.height - trackPx) / 2f
        drawRect(color = track, topLeft = Offset(0f, trackTop), size = Size(size.width, trackPx))
        drawRect(color = fill, topLeft = Offset(0f, trackTop), size = Size(size.width * fraction, trackPx))
        if (showScrubDot) {
            val radius = DOT_SIZE.toPx() / 2f
            val cx = (size.width * fraction).coerceIn(radius, size.width - radius)
            drawCircle(color = accent, radius = radius, center = Offset(cx, size.height / 2f))
        }
    }
}

private val TRACK_THICKNESS = 1.dp
private val DOT_SIZE = 5.dp
