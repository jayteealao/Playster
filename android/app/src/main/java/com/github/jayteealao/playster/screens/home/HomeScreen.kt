package com.github.jayteealao.playster.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.Dateline
import com.github.jayteealao.playster.ui.editorial.components.Deck
import com.github.jayteealao.playster.ui.editorial.components.DisplayTitle
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBarAction
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialProgressBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import com.github.jayteealao.playster.ui.editorial.components.ShelfRow
import com.github.jayteealao.playster.ui.editorial.components.datelineStyle
import com.github.jayteealao.playster.ui.editorial.components.metaLineStyle

/**
 * Home — the mock's "Reading Room" front page over real (seeded) data:
 * masthead, continue-listening headliner, and the playlist shelf dressed as
 * volumes. Every visual is a component-library primitive; the screen is pure
 * composition, which is what makes the first full-screen pixel gate a fair
 * test of the design system rather than of bespoke Home code.
 */
@Composable
fun HomeScreen(
    onOpenPlaylist: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onOpenPlaylist = onOpenPlaylist,
        onOpenPlayer = onOpenPlayer,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

private val SCREEN_HORIZONTAL = 22.dp

@Composable
fun HomeContent(
    state: HomeUiState,
    onOpenPlaylist: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EditorialAppBar(
            kicker = "Playster · Reading Room",
            left = {
                EditorialAppBarAction(
                    icon = EditorialIcons.List,
                    contentDescription = "Reader preferences",
                    onClick = onOpenSettings,
                    iconSize = 18.dp,
                )
            },
            right = {
                EditorialAppBarAction(
                    icon = EditorialIcons.Search,
                    contentDescription = "Search",
                    onClick = onOpenSearch,
                )
            },
        )
        when (state) {
            is HomeUiState.Loading ->
                EditorialLoadingNotice(
                    label = "Your reading room is being composed.",
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is HomeUiState.Empty ->
                EditorialEmptyNotice(
                    title = "Your shelf is empty.",
                    teaches = "Playlists you add will line up here, most recently opened first — a few minutes each.",
                    actionLabel = "Find something to read",
                    onAction = onOpenSearch,
                    modifier =
                        Modifier
                            .padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp)
                            .testTag("home-empty"),
                )

            is HomeUiState.Error ->
                EditorialErrorNotice(
                    message = "The shelf could not be loaded.",
                    actionLabel = "Try again",
                    onAction = onRetry,
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is HomeUiState.Content ->
                HomeShelf(
                    content = state,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenPlayer = onOpenPlayer,
                )
        }
    }
}

@Composable
private fun HomeShelf(
    content: HomeUiState.Content,
    onOpenPlaylist: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "masthead") {
            MastheadBlock(content.masthead)
        }
        content.headliner?.let { headliner ->
            item(key = "headliner") {
                HeadlinerBlock(
                    headliner = headliner,
                    onClick = { onOpenPlayer(headliner.videoId) },
                )
            }
        }
        item(key = "shelf-header") {
            ShelfHeader()
        }
        items(content.shelf, key = { it.playlistId }) { entry ->
            ShelfRow(
                ordinal = entry.ordinal,
                kicker = entry.kicker,
                title = entry.title,
                byline = entry.byline,
                progress = entry.progress,
                onClick = { onOpenPlaylist(entry.playlistId) },
                modifier =
                    Modifier
                        .padding(horizontal = SCREEN_HORIZONTAL)
                        .testTag("home-shelf-row-${entry.playlistId}"),
            )
        }
        item(key = "terminator") {
            ShelfTerminator()
        }
    }
}

@Composable
private fun MastheadBlock(masthead: Masthead) {
    Column(
        modifier =
            Modifier.padding(
                start = SCREEN_HORIZONTAL,
                end = SCREEN_HORIZONTAL,
                top = 6.dp,
                bottom = 4.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Kicker(text = masthead.issueLine)
            Kicker(text = masthead.unreadLabel, accent = true)
        }
        Spacer(Modifier.height(6.dp))
        DisplayTitle(
            text =
                buildAnnotatedString {
                    append(masthead.titleTop)
                    append("\n")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(masthead.titleEmphasis)
                    }
                },
            sizeSp = 32.0,
        )
        Spacer(Modifier.height(8.dp))
        Deck(text = masthead.deck)
    }
}

@Composable
private fun HeadlinerBlock(
    headliner: Headliner,
    onClick: () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            Modifier
                .padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 16.dp, bottom = 6.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("home-headliner"),
    ) {
        EditorialRule()
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Kicker(text = headliner.episodeLabel, accent = true)
                Spacer(Modifier.height(4.dp))
                DisplayTitle(text = headliner.title, sizeSp = 19.0)
                Spacer(Modifier.height(4.dp))
                Dateline(text = headliner.meta)
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorialProgressBar(
                        progress = headliner.progress,
                        fill = tokens.palette.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${headliner.positionLabel} / ${headliner.durationLabel}",
                        style = tokens.metaLineStyle(HEADLINER_META_SP),
                        color = tokens.palette.inkFaint,
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .padding(top = 14.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(tokens.palette.ink),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = EditorialIcons.Play,
                    contentDescription = null,
                    tint = tokens.palette.paper,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ShelfHeader() {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 20.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Kicker(text = "The Shelf")
        Text(
            text = "in order of last opened",
            style = tokens.datelineStyle(),
            color = tokens.palette.inkFaint,
        )
    }
}

@Composable
private fun ShelfTerminator() {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier = Modifier.padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, top = 4.dp),
    ) {
        EditorialRule()
        Text(
            text = "— end of shelf —",
            style = tokens.datelineStyle(),
            color = tokens.palette.inkFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

private const val HEADLINER_META_SP = 9.5
