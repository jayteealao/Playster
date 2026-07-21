@file:Suppress("TooManyFunctions")

package com.github.jayteealao.playster.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.EditorialTokens
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialChip
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import com.github.jayteealao.playster.ui.editorial.edScaled

/**
 * The stateless Search composition — the pixel-gate + golden subject. The
 * hybrid split is drawn plainly: the transcript group carries its own
 * loading/error/empty/results sub-tree beneath the instant title results, so a
 * backend failure reads as an editorial error for that group while the title
 * rows keep showing (R2). Primitives only; no Material vocabulary.
 *
 * (File-level `TooManyFunctions` is suppressed above: this is one small private
 * composable per result kind + gap surface — cohesive, not sprawling.)
 */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRecentTap: (String) -> Unit,
    onOpenTranscriptAt: (videoId: String, startSeconds: Double) -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.palette.paper)
                .testTag("search-content"),
    ) {
        EditorialAppBar(kicker = "Find anywhere")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item(key = "field") {
                SearchField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    onClearQuery = onClearQuery,
                )
            }
            if (state.hasQuery) {
                item(key = "count") { ResultCountLine(state) }
            }

            if (SearchStateAssembler.isFilteredEmpty(state)) {
                item(key = "filtered-empty") { FilteredEmpty(query = state.query, onClear = onClearQuery) }
            } else {
                transcriptGroup(state.transcript, onOpenTranscriptAt)
                items(state.videos, key = { "video-${it.videoId}" }) { video ->
                    VideoRow(video = video, onClick = { onOpenPlayer(video.videoId) })
                }
                items(state.playlists, key = { "playlist-${it.playlistId}" }) { playlist ->
                    PlaylistRow(playlist = playlist, onClick = { onOpenPlaylist(playlist.playlistId) })
                }
            }

            if (state.recents.isNotEmpty()) {
                item(key = "recents") { RecentlySearched(recents = state.recents, onRecentTap = onRecentTap) }
            }
        }
    }
}

private val SCREEN_HORIZONTAL = 22.dp

private fun searchInputStyle(tokens: EditorialTokens): TextStyle =
    TextStyle(
        fontFamily = tokens.face.display,
        fontStyle = FontStyle.Italic,
        fontSize = edScaled(INPUT_SP, tokens.sizeStep.multiplier).sp,
        color = tokens.palette.ink,
    )

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = Modifier.padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = EditorialIcons.Search,
                contentDescription = null,
                tint = tokens.palette.ink,
                modifier = Modifier.size(16.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search transcripts, videos, playlists…",
                        style = searchInputStyle(tokens).copy(color = tokens.palette.inkFaint),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = searchInputStyle(tokens),
                    cursorBrush = SolidColor(tokens.accent.color),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("search-input"),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .clickable(role = Role.Button, onClick = onClearQuery)
                            .padding(4.dp)
                            .testTag("search-clear"),
                ) {
                    Icon(
                        imageVector = EditorialIcons.Close,
                        contentDescription = "Clear search",
                        tint = tokens.palette.inkFaint,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        EditorialRule(color = tokens.palette.ink, thickness = 1.5.dp)
    }
}

@Composable
private fun ResultCountLine(state: SearchUiState) {
    val tokens = LocalEditorialTokens.current
    val transcriptCount = (state.transcript as? TranscriptSearchState.Results)?.items?.size ?: 0
    val total = state.titleResultCount + transcriptCount
    val label =
        when (state.transcript) {
            TranscriptSearchState.Loading -> "$total so far · searching transcripts…"
            else -> if (total == 1) "1 result" else "$total results"
        }
    Text(
        text = label,
        style =
            TextStyle(
                fontFamily = tokens.sans,
                fontSize = edScaled(COUNT_SP, tokens.sizeStep.multiplier).sp,
                letterSpacing = 0.4.sp,
            ),
        color = tokens.palette.inkFaint,
        modifier = Modifier.padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 8.dp, bottom = 4.dp),
    )
}

/** The transcript backend leg — its own loading/error/empty/results sub-tree. */
private fun LazyListScope.transcriptGroup(
    state: TranscriptSearchState,
    onOpenTranscriptAt: (videoId: String, startSeconds: Double) -> Unit,
) {
    when (state) {
        TranscriptSearchState.Idle -> Unit
        TranscriptSearchState.Empty -> Unit
        TranscriptSearchState.Loading ->
            item(key = "transcript-loading") {
                EditorialLoadingNotice(
                    label = "Searching the transcripts…",
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 12.dp),
                )
            }
        is TranscriptSearchState.Error ->
            item(key = "transcript-error") { TranscriptGroupError(message = state.message) }
        is TranscriptSearchState.Results ->
            items(state.items, key = { "transcript-${it.videoId}-${it.startSeconds}" }) { hit ->
                TranscriptRow(hit = hit, onClick = { onOpenTranscriptAt(hit.videoId, hit.startSeconds) })
            }
    }
}

/**
 * The transcript-group error surface — the editorial error voice (2dp accent top
 * rule + specific italic message) without a retry action: the search re-runs on
 * the next keystroke, and the title results above stay rendered (R2).
 */
@Composable
private fun TranscriptGroupError(message: String) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SCREEN_HORIZONTAL, vertical = 12.dp)
                .testTag("search-transcript-error"),
    ) {
        EditorialRule(color = tokens.accent.color, thickness = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text(text = message, style = tokens.type.deck, color = tokens.palette.ink)
        Spacer(Modifier.height(10.dp))
        EditorialRule(color = tokens.palette.ruleFaint)
    }
}

@Composable
private fun TranscriptRow(
    hit: TranscriptResult,
    onClick: () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    ResultShell(onClick = onClick, testTag = "search-result-transcript-${hit.videoId}") {
        Kicker(text = "In transcript", accent = true)
        Spacer(Modifier.height(4.dp))
        ResultTitle(hit.title)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(tokens.accent.soft),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "“${hit.snippet}”",
                    style =
                        tokens.type.body.copy(
                            fontStyle = FontStyle.Italic,
                            fontSize = edScaled(SNIPPET_SP, tokens.sizeStep.multiplier).sp,
                        ),
                    color = tokens.palette.inkSoft,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hit.jumpLabel,
                    style =
                        TextStyle(
                            fontFamily = tokens.sans,
                            fontSize = edScaled(JUMP_SP, tokens.sizeStep.multiplier).sp,
                            fontWeight = FontWeight.W700,
                            letterSpacing = 0.4.sp,
                        ),
                    color = tokens.accent.color,
                )
            }
        }
    }
}

@Composable
private fun VideoRow(
    video: VideoResult,
    onClick: () -> Unit,
) {
    ResultShell(onClick = onClick, testTag = "search-result-video-${video.videoId}") {
        Kicker(text = "A video", accent = true)
        Spacer(Modifier.height(4.dp))
        ResultTitle(video.title)
        if (video.meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            MetaLine(video.meta)
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistResult,
    onClick: () -> Unit,
) {
    ResultShell(onClick = onClick, testTag = "search-result-playlist-${playlist.playlistId}") {
        Kicker(text = "A playlist", accent = true)
        Spacer(Modifier.height(4.dp))
        ResultTitle(playlist.title)
        if (playlist.meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            MetaLine(playlist.meta)
        }
    }
}

@Composable
private fun ResultShell(
    onClick: () -> Unit,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(testTag)
                .padding(horizontal = SCREEN_HORIZONTAL),
    ) {
        EditorialRule()
        Spacer(Modifier.height(14.dp))
        content()
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun ResultTitle(text: String) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style = tokens.type.display.copy(fontSize = edScaled(RESULT_TITLE_SP, tokens.sizeStep.multiplier).sp),
        color = tokens.palette.ink,
    )
}

@Composable
private fun MetaLine(text: String) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style =
            tokens.type.body.copy(
                fontStyle = FontStyle.Italic,
                fontSize = edScaled(META_SP, tokens.sizeStep.multiplier).sp,
            ),
        color = tokens.palette.inkSoft,
    )
}

@Composable
private fun FilteredEmpty(
    query: String,
    onClear: () -> Unit,
) {
    EditorialEmptyNotice(
        title = "Nothing for “$query”.",
        teaches = "No titles or transcripts match that yet — try a shorter phrase or a channel name.",
        actionLabel = "Clear the search",
        onAction = onClear,
        modifier =
            Modifier
                .padding(horizontal = SCREEN_HORIZONTAL, vertical = 12.dp)
                .testTag("search-filtered-empty"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentlySearched(
    recents: List<String>,
    onRecentTap: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 20.dp)) {
        Kicker(text = "Recently searched")
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            recents.forEach { recent ->
                EditorialChip(
                    text = recent,
                    onClick = { onRecentTap(recent) },
                    modifier = Modifier.testTag("search-recent-$recent"),
                )
            }
        }
    }
}

private const val INPUT_SP = 22.0
private const val RESULT_TITLE_SP = 15.0
private const val SNIPPET_SP = 12.0
private const val JUMP_SP = 9.5
private const val META_SP = 11.0
private const val COUNT_SP = 10.0
