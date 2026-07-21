package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.screens.player.ChapterEntry
import com.github.jayteealao.playster.screens.player.PlayerContent
import com.github.jayteealao.playster.screens.player.PlayerHeader
import com.github.jayteealao.playster.screens.player.PlayerNote
import com.github.jayteealao.playster.screens.player.PlayerUiState
import com.github.jayteealao.playster.screens.player.playback.PlaybackError
import com.github.jayteealao.playster.screens.player.playback.PlaybackState
import com.github.jayteealao.playster.screens.videoDetail.summary.SummaryUiState
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.PaperPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC2/AC10 machine leg: the Player renders editorially over the Masthead-band
 * panel (Probe B) across palettes and states — the summary sealed states, the
 * chapters list + empty state, the notes composer, the collapsed panel, and the
 * editorial playback-error surface that replaces the player area (no fake seek
 * bar). Stateless [PlayerContent] is the subject, so every state renders without
 * playback/Hilt/nav, and no rendered pixel is ever pure #FFFFFF/#000000.
 *
 * Record: ./gradlew recordRoborazziDebug   Verify: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class PlayerStateRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun content_allPalettes() = captureOnAllPalettes("content", seededContent(), PlaybackState.Ready, TAB_SUMMARY)

    @Test
    fun summary_in_progress() =
        captureOnCream("summary_in_progress", seededContent(summary = SummaryUiState.InProgress), PlaybackState.Ready, TAB_SUMMARY)

    @Test
    fun summary_failed_permanent() =
        captureOnCream(
            "summary_failed_permanent",
            seededContent(summary = SummaryUiState.FailedPermanent("This video can't be summarized.")),
            PlaybackState.Ready,
            TAB_SUMMARY,
        )

    @Test
    fun chapters_populated() = captureOnCream("chapters", seededContent(), PlaybackState.Playing, TAB_CHAPTERS)

    @Test
    fun chapters_empty() = captureOnCream("chapters_empty", seededContent(chapters = emptyList()), PlaybackState.Playing, TAB_CHAPTERS)

    @Test
    fun notes_populated() = captureOnCream("notes", seededContent(), PlaybackState.Ready, TAB_NOTES)

    @Test
    fun notes_empty() = captureOnCream("notes_empty", seededContent(notes = emptyList()), PlaybackState.Ready, TAB_NOTES)

    @Test
    fun panel_collapsed() = captureOnCream("panel_collapsed", seededContent(), PlaybackState.Playing, TAB_SUMMARY, expanded = false)

    @Test
    fun playback_error() = captureOnCream("playback_error", seededContent(), PlaybackState.Error(PlaybackError.EmbedDisabled), TAB_SUMMARY)

    @Test
    fun playback_loading() = captureOnCream("playback_loading", seededContent(), PlaybackState.Loading, TAB_SUMMARY)

    @Test
    fun state_loading() = captureState("state_loading", PlayerUiState.Loading)

    @Test
    fun state_error() = captureState("state_error", PlayerUiState.Error)

    private fun captureState(
        key: String,
        state: PlayerUiState,
    ) {
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) {
                PlayerScaffold(state, PlaybackState.Loading, TAB_SUMMARY, expanded = true)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/player_state_$key.png")
        assertNoPurePixels(composeTestRule, "player_state/$key")
        assertRootBackground(composeTestRule, "player_state/$key", EditorialPalettes.Cream.paper)
    }

    private fun captureOnCream(
        key: String,
        state: PlayerUiState,
        playback: PlaybackState,
        tab: Int,
        expanded: Boolean = true,
    ) {
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) {
                PlayerScaffold(state, playback, tab, expanded)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/player_state_$key.png")
        assertNoPurePixels(composeTestRule, "player_state/$key")
        assertRootBackground(composeTestRule, "player_state/$key", EditorialPalettes.Cream.paper)
    }

    private fun captureOnAllPalettes(
        key: String,
        state: PlayerUiState,
        playback: PlaybackState,
        tab: Int,
    ) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)
        composeTestRule.setContent {
            EditorialTheme(palette = palette) {
                PlayerScaffold(state, playback, tab, expanded = true)
            }
        }
        for (nextPalette in EditorialPalettes.All) {
            palette = nextPalette
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/snapshots/images/player_state_${key}_${nextPalette.key}.png",
            )
            assertNoPurePixels(composeTestRule, "player_state/$key/${nextPalette.key}")
            assertRootBackground(composeTestRule, "player_state/$key/${nextPalette.key}", nextPalette.paper)
        }
    }

    @androidx.compose.runtime.Composable
    private fun PlayerScaffold(
        state: PlayerUiState,
        playback: PlaybackState,
        tab: Int,
        expanded: Boolean,
    ) {
        PlayerContent(
            state = state,
            playbackState = playback,
            positionSeconds = 372f,
            durationSeconds = 1394f,
            panelExpanded = expanded,
            onTogglePanel = {},
            onScrub = {},
            onSetSpeed = {},
            onPlayPause = {},
            onJumpToChapter = {},
            onCreateNote = {},
            onBack = {},
            onOpenTranscript = {},
            onRetry = {},
            videoSlot = { m -> androidx.compose.foundation.layout.Box(m.then(Modifier.background(Color(0xFF15130F)))) },
            initialTab = tab,
        )
    }

    private fun seededContent(
        summary: SummaryUiState =
            SummaryUiState.Completed(
                content =
                    "Design systems plateau when they're treated as a library instead of a contract. " +
                        "The next decade belongs to runtime systems — tokens that resolve at render time and " +
                        "adoption measured in deleted code.",
                model = "free",
            ),
        chapters: List<ChapterEntry> = seededChapters(),
        notes: List<PlayerNote> = seededNotes(),
    ): PlayerUiState.Content =
        PlayerUiState.Content(
            videoId = "ed-v09",
            playlistId = "ed-p1",
            header =
                PlayerHeader(
                    kicker = "Ep 3 / 14 · Design",
                    title = "The Future of Design Systems",
                    byline = "by Joey Banks",
                    meta = "Nov 2, 2025 · 128,400 views",
                ),
            summary = summary,
            chapters = chapters,
            notes = notes,
            resumeSeconds = 372f,
            folioLeft = "Design · Designing Better Interfaces",
            folioRight = "3 / 14",
        )

    private fun seededChapters(): List<ChapterEntry> =
        listOf(
            ChapterEntry("0:00", "Why systems plateau", "3:42", 0f),
            ChapterEntry("3:42", "Tokens at render time", "8:10", 222f),
            ChapterEntry("11:52", "Adoption as deleted code", "", 712f),
        )

    private fun seededNotes(): List<PlayerNote> =
        listOf(
            PlayerNote("AT 3:42", "The runtime-tokens idea — draft this for the Button at work."),
            PlayerNote("AT 12:47", "“Adoption measured in deleted code.” Steal this line."),
        )

    private companion object {
        const val TAB_SUMMARY = 0
        const val TAB_CHAPTERS = 1
        const val TAB_NOTES = 3
    }
}
