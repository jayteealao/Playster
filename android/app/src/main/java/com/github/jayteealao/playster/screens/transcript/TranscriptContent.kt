package com.github.jayteealao.playster.screens.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.Dateline
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBarAction
import com.github.jayteealao.playster.ui.editorial.components.EditorialEmptyNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.EditorialTextAction
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import com.github.jayteealao.playster.ui.editorial.components.MiniPlayerPill

/**
 * The stateless Transcript article — golden-testable without playback, Hilt, or
 * nav (the embed and playback wiring arrive as the [embedSlot] + the [position]
 * State). It draws the AppBar (back → player, "Transcript · Following/Paused"
 * kicker), the mini-embed slot (the Masthead-strip band), the dateline, a single
 * keyed [LazyColumn] of paragraph rows with the terminator line, and the
 * floating mini-player pill.
 *
 * The active line is derived here via [derivedStateOf] over [position], so the
 * per-second tick recomposes only the rows whose active-ness flips and the pill's
 * readout — never the list body (Step 9). Auto-scroll follows the active line on
 * entry and as it advances; a manual drag pauses following, and "Resume
 * following" re-arms it (Assumption 3 contention rule).
 */
@Composable
@Suppress("LongParameterList") // Stateless screen glue: state + the embed slot + the playback plumbing.
fun TranscriptContent(
    state: TranscriptUiState,
    header: TranscriptHeader?,
    position: State<Float>,
    playing: Boolean,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleHighlight: (TranscriptParagraph) -> Unit,
    onPlayPause: () -> Unit,
    onCreateNote: (String) -> Unit,
    embedSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var following by remember { mutableStateOf(true) }
    var composing by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().testTag("transcript-content")) {
        EditorialAppBar(
            kicker = "Transcript · " + if (following) "Following" else "Paused",
            left = {
                EditorialAppBarAction(
                    icon = EditorialIcons.Back,
                    contentDescription = "Back to the player",
                    onClick = onBack,
                )
            },
            right = {
                // The transcript's identity write: note this line at the live position
                // (AC3). Enumerated deviation — the mock's right slot drew a text-size
                // icon; type sizing lives in Settings, so the actionable affordance here
                // is note-taking.
                EditorialAppBarAction(
                    icon = EditorialIcons.Bookmark,
                    contentDescription = if (composing) "Close the note" else "Add a note here",
                    onClick = { composing = !composing },
                    modifier = Modifier.testTag("transcript-add-note"),
                )
            },
        )
        when (state) {
            is TranscriptUiState.Loading ->
                EditorialLoadingNotice(
                    label = "The transcript is being set, line by line.",
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is TranscriptUiState.Unavailable ->
                EditorialEmptyNotice(
                    title = "No transcript yet",
                    teaches = "When this episode is transcribed, its lines will read here.",
                    actionLabel = "Open the player",
                    onAction = onOpenPlayer,
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is TranscriptUiState.Error ->
                EditorialErrorNotice(
                    message = state.message,
                    actionLabel = "Open the player",
                    onAction = onOpenPlayer,
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is TranscriptUiState.Available ->
                AvailableBody(
                    paragraphs = state.paragraphs,
                    header = header,
                    position = position,
                    playing = playing,
                    following = following,
                    onFollowingChange = { following = it },
                    composing = composing,
                    onComposingChange = { composing = it },
                    onOpenPlayer = onOpenPlayer,
                    onSeek = onSeek,
                    onToggleHighlight = onToggleHighlight,
                    onPlayPause = onPlayPause,
                    onCreateNote = onCreateNote,
                    embedSlot = embedSlot,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun AvailableBody(
    paragraphs: List<TranscriptParagraph>,
    header: TranscriptHeader?,
    position: State<Float>,
    playing: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    composing: Boolean,
    onComposingChange: (Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleHighlight: (TranscriptParagraph) -> Unit,
    onPlayPause: () -> Unit,
    onCreateNote: (String) -> Unit,
    embedSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val starts = remember(paragraphs) { paragraphs.map { it.segmentStart } }
    val activeIndexState = remember(starts) { derivedStateOf { ActiveLineIndex.activeIndex(position.value, starts) } }

    // Follow the active line on entry and as it advances — but never while paused.
    androidx.compose.runtime.LaunchedEffect(activeIndexState.value, following) {
        val target = activeIndexState.value
        if (following && target >= 0 && target < paragraphs.size) {
            listState.animateScrollToItem(target)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize()) {
            embedSlot()
            header?.let { TranscriptDateline(it) }
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // A user drag anywhere on the list pauses following (Assumption 3).
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onFollowingChange(false)
                            }
                        }
                        .testTag("transcript-list"),
                contentPadding = PaddingValues(horizontal = SCREEN_HORIZONTAL, vertical = 8.dp),
            ) {
                items(paragraphs, key = { it.segmentStart }) { paragraph ->
                    val index = starts.indexOf(paragraph.segmentStart)
                    val active by remember(index) { derivedStateOf { activeIndexState.value == index } }
                    TranscriptRow(
                        paragraph = paragraph,
                        active = active,
                        onSeek = { onSeek(paragraph.segmentStart) },
                        onToggleHighlight = { onToggleHighlight(paragraph) },
                    )
                }
                item {
                    Text(
                        text = "— transcript continues —",
                        style = LocalEditorialTokens.current.type.deck.copy(fontStyle = FontStyle.Italic),
                        color = LocalEditorialTokens.current.palette.inkFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (composing) {
                TranscriptNoteComposer(
                    timeLabel = formatClock(position.value),
                    onSave = {
                        onCreateNote(it)
                        onComposingChange(false)
                    },
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            if (!following) {
                EditorialTextAction(
                    text = "Resume following",
                    onClick = { onFollowingChange(true) },
                    icon = EditorialIcons.Next,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("transcript-resume-following"),
                )
            }
            MiniPlayerPill(
                title = header?.kicker ?: "Now playing",
                position = remember { derivedStateOf { formatClock(position.value) } }.value,
                playing = playing,
                onClick = onOpenPlayer,
                onPlayPause = onPlayPause,
                modifier = Modifier.testTag("transcript-mini-player"),
            )
        }
    }
}

@Composable
private fun TranscriptDateline(header: TranscriptHeader) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SCREEN_HORIZONTAL, vertical = 10.dp),
    ) {
        Kicker(text = header.kicker, accent = true)
        if (header.byline.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Dateline(text = header.byline)
        }
    }
}

/**
 * The bare editorial note composer — same idiom as the Player's NotesTab (no
 * Material TextField, the design bans the vocabulary). Stamps the note at the
 * live position; a saved note flows into the transcript marginalia here and the
 * Player + Playlist Notes tabs via the untouched read listener (AC3).
 */
@Composable
private fun TranscriptNoteComposer(
    timeLabel: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    var draft by remember { mutableStateOf("") }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(tokens.palette.paper)
                .testTag("transcript-note-composer"),
    ) {
        EditorialRule(color = tokens.accent.color, thickness = 2.dp)
        Spacer(Modifier.height(8.dp))
        Kicker(text = "Note at $timeLabel", accent = true)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(tokens.palette.paperDeep)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "Write what this line made you think…",
                    style = tokens.type.deck.copy(fontStyle = FontStyle.Italic),
                    color = tokens.palette.inkFaint,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = tokens.type.body.copy(color = tokens.palette.ink),
                cursorBrush = SolidColor(tokens.accent.color),
                modifier = Modifier.fillMaxWidth().testTag("transcript-note-input"),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EditorialTextAction(
                text = "Save note",
                icon = EditorialIcons.Check,
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSave(text)
                        draft = ""
                    }
                },
                modifier = Modifier.testTag("transcript-note-save"),
            )
        }
    }
}

private val SCREEN_HORIZONTAL = 22.dp
private const val SECONDS_PER_MINUTE = 60L

/** M:SS for the pill readout — matches the transcript gutter format. */
internal fun formatClock(seconds: Float): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    return "%d:%02d".format(total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
}
