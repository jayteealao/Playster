package com.github.jayteealao.playster.screens.player.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.screens.player.ChapterEntry
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.Kicker

/**
 * The Chapters tab: a rule-separated list of `time · title · duration` rows with
 * the NOW badge on the chapter the playback position falls in ([nowIndex],
 * tracked live off the position stream). When neither the description nor the
 * summarizer yielded chapters, the editorial empty state teaches rather than
 * showing a blank pane — the resolver's non-blocking guarantee made visible.
 */
@Composable
fun ChaptersTab(
    chapters: List<ChapterEntry>,
    nowIndex: Int,
    onJump: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chapters.isEmpty()) {
        EditorialEmptyNotice(
            title = "No chapters for this one.",
            teaches =
                "When a video's description or summary marks its sections, they'll " +
                    "appear here — tap any to jump straight to that moment.",
            actionLabel = "Read the summary instead",
            onAction = {},
            modifier = modifier.padding(vertical = 14.dp).testTag("player-chapters-empty"),
        )
        return
    }
    Column(modifier = modifier.testTag("player-chapters")) {
        chapters.forEachIndexed { index, chapter ->
            ChapterRow(
                chapter = chapter,
                isNow = index == nowIndex,
                onClick = { onJump(chapter.startSeconds) },
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterEntry,
    isNow: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("player-chapter"),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = chapter.timeLabel,
                style =
                    TextStyle(
                        fontFamily = tokens.sans,
                        fontSize = 11.sp,
                        letterSpacing = 0.3.sp,
                        fontFeatureSettings = "tnum",
                    ),
                color = if (isNow) tokens.accent.color else tokens.palette.inkFaint,
                modifier = Modifier.width(44.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.label,
                    style =
                        tokens.type.body.copy(
                            fontWeight = if (isNow) FontWeight.W600 else FontWeight.W400,
                        ),
                    color = tokens.palette.ink,
                )
                if (chapter.durationLabel.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = chapter.durationLabel,
                        style = tokens.type.folio,
                        color = tokens.palette.inkFaint,
                    )
                }
            }
            if (isNow) {
                Kicker(text = "Now", accent = true, modifier = Modifier.padding(top = 3.dp))
            }
        }
        EditorialRule()
    }
}
