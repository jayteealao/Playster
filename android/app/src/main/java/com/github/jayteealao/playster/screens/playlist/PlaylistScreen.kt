package com.github.jayteealao.playster.screens.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.Dateline
import com.github.jayteealao.playster.ui.editorial.components.Deck
import com.github.jayteealao.playster.ui.editorial.components.DisplayTitle
import com.github.jayteealao.playster.ui.editorial.components.DropcapBody
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBarAction
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialPillButton
import com.github.jayteealao.playster.ui.editorial.components.EditorialQuotaNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialTabs
import com.github.jayteealao.playster.ui.editorial.components.EditorialTextAction
import com.github.jayteealao.playster.ui.editorial.components.EpisodeRow
import com.github.jayteealao.playster.ui.editorial.components.Folio
import com.github.jayteealao.playster.ui.editorial.components.Kicker

/**
 * Playlist — the mock's volume view over real per-playlist data: the cover
 * block, then Episodes / Summary / Notes tabs. Every visual is a
 * component-library primitive, so the screen is pure composition and the
 * summary/quota re-voice reuses the shared sealed model (no Material spinner or
 * snackbar in any tab). Episodes come from this playlist's own `videos`
 * subcollection (killing the mock's shared VIDEO_LIST lie); the Summary tab
 * binds to the representative episode's summary (Assumption A1).
 */
@Composable
fun PlaylistScreen(
    onOpenPlayer: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaylistContent(
        state = state,
        onOpenPlayer = onOpenPlayer,
        onBack = onBack,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

private val SCREEN_HORIZONTAL = 22.dp
private val TABS = listOf("Episodes", "Summary", "Notes")
private const val TAB_EPISODES = 0
private const val TAB_SUMMARY = 1
private const val TAB_NOTES = 2

@Composable
fun PlaylistContent(
    state: PlaylistUiState,
    onOpenPlayer: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = TAB_EPISODES,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    Column(modifier = modifier.fillMaxSize().testTag("playlist-content")) {
        EditorialAppBar(
            kicker = coverKicker(state),
            left = {
                EditorialAppBarAction(
                    icon = EditorialIcons.Back,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
            right = {
                EditorialAppBarAction(
                    icon = EditorialIcons.Bookmark,
                    contentDescription = "Bookmark this volume",
                    onClick = {},
                    iconSize = 15.dp,
                )
            },
        )
        when (state) {
            is PlaylistUiState.Loading ->
                EditorialLoadingNotice(
                    label = "This volume's table of contents is on its way.",
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is PlaylistUiState.Error ->
                EditorialErrorNotice(
                    message = "This volume could not be loaded.",
                    actionLabel = "Try again",
                    onAction = onRetry,
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is PlaylistUiState.Content ->
                VolumeBody(
                    content = state,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    onOpenPlayer = onOpenPlayer,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

private fun coverKicker(state: PlaylistUiState): String =
    when (state) {
        is PlaylistUiState.Content -> state.cover.appBarKicker
        else -> "Volume"
    }

@Composable
private fun VolumeBody(
    content: PlaylistUiState.Content,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "cover") { CoverBlock(content.cover, onOpenPlayer) }
            item(key = "tabs") {
                EditorialTabs(
                    tabs = TABS,
                    selectedIndex = selectedTab,
                    onSelect = onSelectTab,
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL),
                )
            }
            when (selectedTab) {
                TAB_EPISODES -> episodesTab(content, onOpenPlayer)
                TAB_SUMMARY -> item(key = "summary") { SummaryTab(content.summary) }
                TAB_NOTES -> item(key = "notes") { NotesTab(content.notes) }
            }
        }
        Folio(
            left = content.cover.folioLeft,
            right = content.folioRight,
            topRule = true,
        )
    }
}

@Composable
private fun CoverBlock(
    cover: Cover,
    onOpenPlayer: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 4.dp, bottom = 14.dp),
    ) {
        Kicker(text = cover.kicker, accent = true)
        Spacer(Modifier.height(8.dp))
        DisplayTitle(text = cover.title, sizeSp = 26.0)
        Spacer(Modifier.height(8.dp))
        Deck(text = cover.dek)
        Spacer(Modifier.height(10.dp))
        Dateline(text = cover.byline)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditorialPillButton(
                text = cover.continueLabel,
                icon = EditorialIcons.Play,
                onClick = { cover.continueVideoId?.let(onOpenPlayer) },
                modifier = Modifier.testTag("playlist-continue"),
            )
            Spacer(Modifier.width(18.dp))
            EditorialTextAction(
                text = "Download",
                icon = EditorialIcons.Download,
                onClick = {},
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.episodesTab(
    content: PlaylistUiState.Content,
    onOpenPlayer: (String) -> Unit,
) {
    if (content.episodes.isEmpty()) {
        item(key = "episodes-empty") {
            EditorialEmptyNotice(
                title = "No episodes yet.",
                teaches = "When this volume's videos sync, they'll be listed here in order — a few minutes each.",
                actionLabel = "Back to the shelf",
                onAction = {},
                modifier =
                    Modifier
                        .padding(horizontal = SCREEN_HORIZONTAL, vertical = 14.dp)
                        .testTag("playlist-episodes-empty"),
            )
        }
    } else {
        items(content.episodes, key = { it.videoId }) { episode ->
            EpisodeRow(
                position = episode.position,
                title = episode.title,
                duration = episode.durationLabel,
                watched = episode.watched,
                playing = episode.playing,
                onClick = { onOpenPlayer(episode.videoId) },
                modifier =
                    Modifier
                        .padding(horizontal = SCREEN_HORIZONTAL)
                        .testTag("playlist-episode-${episode.videoId}"),
            )
        }
    }
}

@Composable
private fun SummaryTab(summary: SummaryTabState) {
    Column(modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL).testTag("playlist-summary")) {
        if (summary.quotaExhausted) {
            EditorialQuotaNotice(
                message = "Daily summary limit reached. Resets at midnight UTC.",
                modifier = Modifier.padding(top = 14.dp).testTag("quota-banner"),
            )
        }
        Spacer(Modifier.height(14.dp))
        when (val s = summary.summary) {
            is SummaryUiState.Completed -> {
                DropcapBody(text = s.content, modifier = Modifier.testTag("summary-completed"))
                summary.readFirst?.let { readFirst ->
                    Spacer(Modifier.height(14.dp))
                    Kicker(text = "What to read first", accent = true)
                    Spacer(Modifier.height(6.dp))
                    ReadFirstLine(readFirst)
                }
            }
            is SummaryUiState.InProgress ->
                EditorialLoadingNotice(
                    label = "The summary is being written.",
                    modifier = Modifier.testTag("summary-in-progress"),
                )
            is SummaryUiState.NoSummary ->
                EditorialLoadingNotice(
                    label = "No summary has been written for this volume yet.",
                    modifier = Modifier.testTag("summary-none"),
                )
            is SummaryUiState.FailedTransient ->
                EditorialErrorNotice(
                    message = s.message,
                    actionLabel = "Open the episode to retry",
                    onAction = {},
                    modifier = Modifier.testTag("summary-failed-transient"),
                )
            is SummaryUiState.FailedPermanent ->
                EditorialErrorNotice(
                    message = s.message,
                    actionLabel = "Read the episode instead",
                    onAction = {},
                    modifier = Modifier.testTag("summary-failed-permanent"),
                )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReadFirstLine(text: String) {
    val tokens = LocalEditorialTokens.current
    Text(text = text, style = tokens.type.deck, color = tokens.palette.inkSoft)
}

@Composable
private fun NotesTab(notes: List<NoteEntry>) {
    if (notes.isEmpty()) {
        EditorialEmptyNotice(
            title = "No notes yet.",
            teaches =
                "Notes you take while reading or listening — pinned to a moment — " +
                    "will gather here, across the whole volume.",
            actionLabel = "Start reading",
            onAction = {},
            modifier =
                Modifier
                    .padding(horizontal = SCREEN_HORIZONTAL, vertical = 14.dp)
                    .testTag("playlist-notes-empty"),
        )
        return
    }
    Column(modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 14.dp)) {
        Kicker(text = "${notes.size} notes · across this volume")
        Spacer(Modifier.height(8.dp))
        notes.forEach { note -> NoteRow(note) }
    }
}

@Composable
private fun NoteRow(note: NoteEntry) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            text = note.timestampLabel,
            style = tokens.type.kicker,
            color = tokens.accent.color,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = note.text,
            style = tokens.type.body,
            color = tokens.palette.ink,
        )
    }
}
