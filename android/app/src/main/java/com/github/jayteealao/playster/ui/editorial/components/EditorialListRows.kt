package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.EditorialTokens
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.edScaled
import kotlin.math.roundToInt

/*
 * The two editorial list rows, exactly as the Home shelf and the Playlist
 * table of contents draw them. Rows are stateless with immutable params
 * (skippable under LazyColumn); each row is one merged accessibility node
 * with a Button role, and the row's natural height already clears the 48dp
 * touch floor.
 */

/**
 * Playlist episode row: 24dp ordinal column, serif-14 title, sans-9.5
 * duration line. Watched entries dim to 0.5 alpha with a rule-colored
 * strikethrough; the playing entry gets W600, accent meta with "Now
 * playing", and a 10dp accent play glyph.
 */
@Composable
fun EpisodeRow(
    position: Int,
    title: String,
    duration: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    playing: Boolean = false,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .alpha(if (watched) WATCHED_ALPHA else 1f),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Ordinal(
                text = position.toString().padStart(2, '0'),
                sizeSp = EPISODE_ORDINAL_SP,
                active = playing,
                modifier = Modifier.width(24.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                WatchedStrikeText(
                    text = title,
                    style =
                        tokens.episodeTitleStyle().copy(
                            fontWeight = if (playing) FontWeight.W600 else FontWeight.W400,
                        ),
                    struck = watched,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (playing) "$duration · Now playing" else duration,
                    style = tokens.metaLineStyle(EPISODE_META_SP),
                    color = if (playing) tokens.accent.color else tokens.palette.inkFaint,
                )
            }
            if (playing) {
                Icon(
                    imageVector = EditorialIcons.PlayFilled,
                    contentDescription = null,
                    tint = tokens.accent.color,
                    modifier = Modifier.padding(top = 4.dp).size(10.dp),
                )
            }
        }
        EditorialRule()
    }
}

/**
 * Home shelf row: 26dp ordinal, accent kicker (tag · volume), serif-16
 * title, italic byline, and — when [progress] is non-null — a 1dp accent
 * progress rule with a tabular percentage. Consumers omit [progress] for
 * fractions at or below the design's 2% threshold (the row then also drops
 * the reserved space, as the mock does).
 */
@Composable
fun ShelfRow(
    ordinal: Int,
    kicker: String,
    title: String,
    byline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        EditorialRule()
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Ordinal(
                text = ordinal.toString().padStart(2, '0'),
                modifier = Modifier.width(26.dp).padding(top = 3.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Kicker(text = kicker, accent = true)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = title,
                    style = tokens.shelfTitleStyle(),
                    color = tokens.palette.ink,
                )
                Spacer(Modifier.height(4.dp))
                Dateline(text = byline)
                if (progress != null) {
                    Spacer(Modifier.height(7.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EditorialProgressBar(
                            progress = progress,
                            fill = tokens.accent.color,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(progress * 100).roundToInt()}%",
                            style = tokens.metaLineStyle(SHELF_PERCENT_SP),
                            color = tokens.palette.inkFaint,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Title text with the mock's rule-colored line-through for watched rows —
 * Compose's [androidx.compose.ui.text.style.TextDecoration] can only strike
 * in the text color, so the rule-colored line is drawn over each laid-out
 * line instead.
 */
@Composable
private fun WatchedStrikeText(
    text: String,
    style: TextStyle,
    struck: Boolean,
) {
    val tokens = LocalEditorialTokens.current
    val rule = tokens.palette.rule
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = tokens.palette.ink,
        onTextLayout = { layoutResult = it },
        modifier =
            Modifier.drawWithContent {
                drawContent()
                val layout = layoutResult
                if (struck && layout != null) {
                    for (line in 0 until layout.lineCount) {
                        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                        drawLine(
                            color = rule,
                            start = Offset(layout.getLineLeft(line), y),
                            end = Offset(layout.getLineRight(line), y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            },
    )
}

private const val WATCHED_ALPHA = 0.5f
private const val EPISODE_ORDINAL_SP = 12.0
private const val EPISODE_TITLE_SP = 14.0
private const val EPISODE_META_SP = 9.5
private const val SHELF_TITLE_SP = 16.0
private const val SHELF_PERCENT_SP = 9.0

/** Episode title: display serif 14/1.2, no tracking. */
internal fun EditorialTokens.episodeTitleStyle(): TextStyle =
    TextStyle(
        fontFamily = face.display,
        fontSize = edScaled(EPISODE_TITLE_SP, sizeStep.multiplier).sp,
        lineHeight = 1.2.em,
    )

/** Shelf title: display serif 16/1.15, balanced wrap. */
internal fun EditorialTokens.shelfTitleStyle(): TextStyle =
    TextStyle(
        fontFamily = face.display,
        fontSize = edScaled(SHELF_TITLE_SP, sizeStep.multiplier).sp,
        lineHeight = 1.15.em,
        lineBreak = LineBreak.Heading,
    )

/** Sans meta line (durations, percentages): ls 0.4, tabular figures. */
internal fun EditorialTokens.metaLineStyle(sizeSp: Double): TextStyle =
    TextStyle(
        fontFamily = sans,
        fontSize = edScaled(sizeSp, sizeStep.multiplier).sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = TABULAR_NUMS,
    )
