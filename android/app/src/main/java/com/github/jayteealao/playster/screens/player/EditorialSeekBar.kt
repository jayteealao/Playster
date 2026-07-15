package com.github.jayteealao.playster.screens.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialProgressBar

/**
 * The player's scrubbable seek bar. Reuses the display-only
 * [EditorialProgressBar]'s render (1dp track + 5dp accent scrub dot) and adds
 * the drag/tap gesture the primitive deliberately lacks — a new thin composable
 * rather than a gesture bolted onto the golden-covered primitive (Assumption 5).
 * Tapping or dragging maps the touch's x to a 0–1 fraction and reports it to
 * [onScrub]; position and remaining labels track the live position stream.
 *
 * The gesture target is a ≥48dp-tall row so the touch floor is met (`harden.md`)
 * while the 5dp visual stays mock-exact; the bar exposes range semantics and a
 * content description for TalkBack.
 */
@Composable
fun EditorialSeekBar(
    positionSeconds: Float,
    durationSeconds: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    val fraction =
        if (durationSeconds > 0f) (positionSeconds / durationSeconds).coerceIn(0f, 1f) else 0f
    val labelStyle =
        TextStyle(
            fontFamily = tokens.sans,
            fontSize = 10.0.sp,
            letterSpacing = 0.3.sp,
            fontFeatureSettings = "tnum",
        )

    Column(modifier = modifier.fillMaxWidth().testTag("player-seek-bar")) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SEEK_TOUCH_HEIGHT)
                    .pointerInput(durationSeconds) {
                        detectTapGestures { offset ->
                            onScrub((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(durationSeconds) {
                        detectHorizontalDragGestures { change, _ ->
                            onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    .semantics {
                        contentDescription = "Seek bar"
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    },
            contentAlignment = Alignment.Center,
        ) {
            EditorialProgressBar(
                progress = fraction,
                showScrubDot = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = EditorialDressing.clockLabel(positionSeconds.toLong()),
                style = labelStyle,
                color = tokens.palette.inkSoft,
            )
            Text(
                text = "-${EditorialDressing.clockLabel((durationSeconds - positionSeconds).toLong())}",
                style = labelStyle,
                color = tokens.palette.inkFaint,
            )
        }
    }
}

private val SEEK_TOUCH_HEIGHT = 48.dp
