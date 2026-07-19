package com.github.jayteealao.playster.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.data.editorial.EditorialDressing
import com.github.jayteealao.playster.data.firestore.SummaryChapter
import com.github.jayteealao.playster.screens.player.chapters.ChaptersResolver
import com.github.jayteealao.playster.screens.player.playback.PlaybackController
import com.github.jayteealao.playster.screens.player.playback.PlaybackError
import com.github.jayteealao.playster.screens.player.playback.PlaybackInstrumentation
import com.github.jayteealao.playster.screens.player.playback.PlaybackState
import com.github.jayteealao.playster.screens.player.playback.YouTubePlayerHost
import com.github.jayteealao.playster.screens.player.playback.isDeviceOffline
import com.github.jayteealao.playster.screens.player.tabs.ChaptersTab
import com.github.jayteealao.playster.screens.player.tabs.NotesTab
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.Dateline
import com.github.jayteealao.playster.ui.editorial.components.Deck
import com.github.jayteealao.playster.ui.editorial.components.DisplayTitle
import com.github.jayteealao.playster.ui.editorial.components.DropcapBody
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBarAction
import com.github.jayteealao.playster.ui.editorial.components.EditorialErrorNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialPillButton
import com.github.jayteealao.playster.ui.editorial.components.EditorialTabs
import com.github.jayteealao.playster.ui.editorial.components.Folio
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import kotlinx.coroutines.delay

/**
 * Player — the mock's article-page player over real, compliant YouTube playback.
 * The Masthead-band [VideoPanel] pins the embed to the top; the article header,
 * scrubbable seek bar, speed control, and the Summary / Chapters / Transcript /
 * Notes tabs read below it. Every visual is a component-library primitive or the
 * PO-pinned panel, so the screen is composition; playback is the remembered
 * [PlaybackController] over one WebView lifecycle.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        is PlayerUiState.Content ->
            LivePlayer(
                content = current,
                viewModel = viewModel,
                onBack = onBack,
                onOpenTranscript = onOpenTranscript,
                modifier = modifier,
            )
        else ->
            PlayerContent(
                state = state,
                playbackState = PlaybackState.Loading,
                positionSeconds = 0f,
                durationSeconds = 0f,
                panelExpanded = true,
                onTogglePanel = {},
                onScrub = {},
                onSetSpeed = {},
                onPlayPause = {},
                onJumpToChapter = {},
                onCreateNote = {},
                onBack = onBack,
                onOpenTranscript = {},
                onRetry = viewModel::retry,
                videoSlot = {},
                modifier = modifier,
            )
    }
}

@Composable
private fun LivePlayer(
    content: PlayerUiState.Content,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller =
        remember(content.videoId) {
            // The controller is owned by the activity-shared session, not this
            // composition — so the still-live embed survives Player→Transcript nav.
            viewModel.playbackSession.controllerFor(
                videoId = content.videoId,
                startPositionSeconds = content.resumeSeconds,
                isOffline = { isDeviceOffline(context) },
            )
        }
    val playbackState by controller.state.collectAsStateWithLifecycle()
    val position by controller.positionSeconds.collectAsStateWithLifecycle()
    val duration by controller.durationSeconds.collectAsStateWithLifecycle()
    var panelExpanded by rememberSaveable(content.videoId) { mutableStateOf(true) }

    // Load watchdog (AC4): if the embed never reaches ready within the window —
    // a network loss during initial load fires neither onReady nor onError — the
    // controller would sit in Loading forever ("Cueing the recording…"). Time it
    // out into the editorial error surface. Keyed on playbackState so leaving
    // Loading cancels the pending timeout; re-arms if it re-enters Loading.
    LaunchedEffect(playbackState) {
        if (playbackState is PlaybackState.Loading) {
            delay(LOAD_TIMEOUT_MS)
            controller.onLoadTimedOut()?.let { error ->
                PlaybackInstrumentation.onLoadTimeout(
                    content.videoId,
                    offline = error is PlaybackError.Offline,
                )
            }
        }
    }

    // Forward every position tick to the VM; the throttle inside prevents any
    // per-second write (AC5). A play→pause transition writes immediately.
    LaunchedEffect(position, playbackState) {
        viewModel.onPlaybackTick(position, duration, playbackState is PlaybackState.Playing)
    }
    // Force a final write when the player leaves composition (exit → resume point).
    DisposableEffect(controller) {
        onDispose {
            viewModel.onPlayerStopped(
                controller.positionSeconds.value,
                controller.durationSeconds.value,
            )
        }
    }

    PlayerContent(
        state = content,
        playbackState = playbackState,
        positionSeconds = position,
        durationSeconds = duration,
        panelExpanded = panelExpanded,
        onTogglePanel = { panelExpanded = !panelExpanded },
        onScrub = controller::seekToFraction,
        onSetSpeed = controller::setSpeed,
        onPlayPause = {
            if (playbackState is PlaybackState.Playing) controller.pause() else controller.play()
        },
        onJumpToChapter = controller::seekTo,
        onCreateNote = { text -> viewModel.createNote(controller.positionSeconds.value, text) },
        onBack = onBack,
        onOpenTranscript = { onOpenTranscript(content.videoId) },
        onRetry = viewModel::retry,
        videoSlot = { m -> YouTubePlayerHost(session = viewModel.playbackSession, modifier = m) },
        modifier = modifier,
    )
}

private val SCREEN_HORIZONTAL = 22.dp

/**
 * How long the embed may sit in [PlaybackState.Loading] before the watchdog
 * surfaces the editorial error (AC4). Generous enough to clear a slow-but-real
 * cold IFrame load on a live network, short enough that an offline launch fails
 * to a readable surface rather than an indefinite "Cueing the recording…".
 */
private const val LOAD_TIMEOUT_MS = 8_000L

private val TABS = listOf("Summary", "Chapters", "Transcript", "Notes")
private const val TAB_SUMMARY = 0
private const val TAB_CHAPTERS = 1
private const val TAB_TRANSCRIPT = 2
private const val TAB_NOTES = 3
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
@Suppress("LongParameterList") // Stateless screen glue: playback plumbing + the video slot, each load-bearing.
fun PlayerContent(
    state: PlayerUiState,
    playbackState: PlaybackState,
    positionSeconds: Float,
    durationSeconds: Float,
    panelExpanded: Boolean,
    onTogglePanel: () -> Unit,
    onScrub: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onJumpToChapter: (Float) -> Unit,
    onCreateNote: (String) -> Unit,
    onBack: () -> Unit,
    onOpenTranscript: () -> Unit,
    onRetry: () -> Unit,
    videoSlot: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = TAB_SUMMARY,
) {
    Column(modifier = modifier.fillMaxSize().testTag("player-content")) {
        EditorialAppBar(
            kicker = (state as? PlayerUiState.Content)?.header?.kicker ?: "Now Playing",
            left = {
                EditorialAppBarAction(
                    icon = EditorialIcons.Back,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
        )
        when (state) {
            is PlayerUiState.Loading ->
                EditorialLoadingNotice(
                    label = "The article and its recording are being laid out.",
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is PlayerUiState.Error ->
                EditorialErrorNotice(
                    message = "This episode could not be loaded.",
                    actionLabel = "Try again",
                    onAction = onRetry,
                    modifier = Modifier.padding(horizontal = SCREEN_HORIZONTAL, vertical = 18.dp),
                )

            is PlayerUiState.Content ->
                LoadedBody(
                    content = state,
                    playbackState = playbackState,
                    positionSeconds = positionSeconds,
                    durationSeconds = durationSeconds,
                    panelExpanded = panelExpanded,
                    onTogglePanel = onTogglePanel,
                    onScrub = onScrub,
                    onSetSpeed = onSetSpeed,
                    onPlayPause = onPlayPause,
                    onJumpToChapter = onJumpToChapter,
                    onCreateNote = onCreateNote,
                    onOpenTranscript = onOpenTranscript,
                    onRetry = onRetry,
                    videoSlot = videoSlot,
                    initialTab = initialTab,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun LoadedBody(
    content: PlayerUiState.Content,
    playbackState: PlaybackState,
    positionSeconds: Float,
    durationSeconds: Float,
    panelExpanded: Boolean,
    onTogglePanel: () -> Unit,
    onScrub: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onJumpToChapter: (Float) -> Unit,
    onCreateNote: (String) -> Unit,
    onOpenTranscript: () -> Unit,
    onRetry: () -> Unit,
    videoSlot: @Composable (Modifier) -> Unit,
    initialTab: Int,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    val playbackError = (playbackState as? PlaybackState.Error)?.error

    Column(modifier = modifier) {
        if (playbackError != null) {
            // AC3/AC4: the error surface replaces the player area — no seek bar
            // or fake mini-player pretends playback; the tabs below stay usable.
            EditorialErrorNotice(
                message = playbackError.editorialMessage,
                actionLabel = playbackError.retryLabel ?: "Back",
                onAction = onRetry,
                modifier =
                    Modifier
                        .padding(horizontal = SCREEN_HORIZONTAL, vertical = 16.dp)
                        .testTag("player-error-surface"),
            )
        } else {
            VideoPanel(
                expanded = panelExpanded,
                playing = playbackState is PlaybackState.Playing,
                loading = playbackState is PlaybackState.Loading,
                onToggle = onTogglePanel,
                player = videoSlot,
            )
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SCREEN_HORIZONTAL),
        ) {
            Spacer(Modifier.height(14.dp))
            ArticleHeader(content.header)
            if (playbackError == null) {
                Spacer(Modifier.height(14.dp))
                EditorialSeekBar(
                    positionSeconds = positionSeconds,
                    durationSeconds = durationSeconds,
                    onScrub = onScrub,
                )
                Spacer(Modifier.height(10.dp))
                PlaybackControls(
                    playing = playbackState is PlaybackState.Playing,
                    selectedSpeed = speed,
                    onPlayPause = onPlayPause,
                    onSelectSpeed = {
                        speed = it
                        onSetSpeed(it)
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            EditorialTabs(
                tabs = TABS,
                selectedIndex = selectedTab,
                onSelect = { index ->
                    if (index == TAB_TRANSCRIPT) onOpenTranscript() else selectedTab = index
                },
            )
            Spacer(Modifier.height(4.dp))
            when (selectedTab) {
                TAB_SUMMARY -> SummaryTab(content.summary)
                TAB_CHAPTERS ->
                    ChaptersTab(
                        chapters = content.chapters,
                        nowIndex = ChaptersResolver.nowIndex(chaptersStarts(content), positionSeconds),
                        onJump = onJumpToChapter,
                    )
                TAB_NOTES ->
                    NotesTab(
                        notes = content.notes,
                        currentTimeLabel = EditorialDressing.clockLabel(positionSeconds.toLong()),
                        onCreateNote = onCreateNote,
                    )
                else -> Unit
            }
            Spacer(Modifier.height(16.dp))
        }

        Folio(left = content.folioLeft, right = content.folioRight, topRule = true)
    }
}

/** Adapt the display chapters back to their start seconds for the NOW-index probe. */
private fun chaptersStarts(content: PlayerUiState.Content): List<SummaryChapter> =
    content.chapters.map { SummaryChapter(t = it.startSeconds.toDouble(), label = it.label) }

@Composable
private fun ArticleHeader(header: PlayerHeader) {
    Column(modifier = Modifier.fillMaxWidth().testTag("player-header")) {
        Kicker(text = header.kicker, accent = true)
        Spacer(Modifier.height(8.dp))
        DisplayTitle(text = header.title, sizeSp = 26.0)
        Spacer(Modifier.height(8.dp))
        Dateline(text = header.byline)
        if (header.meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Deck(text = header.meta)
        }
    }
}

@Composable
private fun PlaybackControls(
    playing: Boolean,
    selectedSpeed: Float,
    onPlayPause: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorialPillButton(
            text = if (playing) "Pause" else "Play",
            icon = if (playing) EditorialIcons.Pause else EditorialIcons.Play,
            onClick = onPlayPause,
            modifier = Modifier.testTag("player-play-pause"),
        )
        Spacer(Modifier.width(16.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("player-speed"),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SPEEDS.forEach { rate ->
                val active = rate == selectedSpeed
                Text(
                    text = speedLabel(rate),
                    style =
                        tokens.type.navLabelInactive.copy(
                            fontWeight = if (active) FontWeight.W600 else FontWeight.W500,
                        ),
                    color = if (active) tokens.accent.color else tokens.palette.inkFaint,
                    modifier =
                        Modifier
                            .clickable(role = Role.Button) { onSelectSpeed(rate) }
                            .padding(vertical = 6.dp)
                            .testTag("player-speed-${speedTag(rate)}"),
                )
            }
        }
    }
}

@Composable
private fun SummaryTab(summary: SummaryUiState) {
    Column(modifier = Modifier.fillMaxWidth().testTag("player-summary")) {
        Spacer(Modifier.height(10.dp))
        when (summary) {
            is SummaryUiState.Completed ->
                DropcapBody(text = summary.content, modifier = Modifier.testTag("summary-completed"))
            is SummaryUiState.InProgress ->
                EditorialLoadingNotice(
                    label = "The summary is being written.",
                    modifier = Modifier.testTag("summary-in-progress"),
                )
            is SummaryUiState.NoSummary ->
                EditorialLoadingNotice(
                    label = "No summary has been written for this episode yet.",
                    modifier = Modifier.testTag("summary-none"),
                )
            is SummaryUiState.FailedTransient ->
                EditorialErrorNotice(
                    message = summary.message,
                    actionLabel = "Try again",
                    onAction = {},
                    modifier = Modifier.testTag("summary-failed-transient"),
                )
            is SummaryUiState.FailedPermanent ->
                EditorialErrorNotice(
                    message = summary.message,
                    actionLabel = "Read the transcript instead",
                    onAction = {},
                    modifier = Modifier.testTag("summary-failed-permanent"),
                )
        }
    }
}

private fun speedLabel(rate: Float): String {
    val trimmed = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0').trimEnd('.')
    return "$trimmed×"
}

private fun speedTag(rate: Float): String = rate.toString().replace('.', '_')
