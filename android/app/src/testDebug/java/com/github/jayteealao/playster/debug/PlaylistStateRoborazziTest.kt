package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.screens.playlist.Cover
import com.github.jayteealao.playster.screens.playlist.EpisodeEntry
import com.github.jayteealao.playster.screens.playlist.NoteEntry
import com.github.jayteealao.playster.screens.playlist.PlaylistContent
import com.github.jayteealao.playster.screens.playlist.PlaylistUiState
import com.github.jayteealao.playster.screens.playlist.SummaryTabState
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
 * AC2/AC5: the Playlist tabs render editorially — the summary sealed states
 * (completed/pending/failed/quota) never a Material spinner or snackbar, the
 * edge states (0-video, all-watched, empty notes) with teaching copy — plus a
 * seeded content regression per palette. The stateless [PlaylistContent] is the
 * subject, so every state renders with no Hilt/Firestore/navigation, and no
 * rendered pixel is ever pure #FFFFFF/#000000.
 *
 * Record baselines: ./gradlew recordRoborazziDebug
 * Verify against them: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class PlaylistStateRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // --- Seeded content per palette (Episodes tab) ---
    @Test
    fun content_allPalettes() = captureOnAllPalettes("content", seededContent(), initialTab = TAB_EPISODES)

    // --- Summary sealed states (Cream) ---
    @Test
    fun summary_completed() = captureOnCream("summary_completed", seededContent(), TAB_SUMMARY)

    @Test
    fun summary_pending() {
        captureOnCream("summary_pending", seededContent(summary = SummaryUiState.InProgress), TAB_SUMMARY)
    }

    @Test
    fun summary_failed_transient() =
        captureOnCream(
            "summary_failed_transient",
            seededContent(summary = SummaryUiState.FailedTransient("Couldn't summarize. Try again.")),
            TAB_SUMMARY,
        )

    @Test
    fun summary_failed_permanent() =
        captureOnCream(
            "summary_failed_permanent",
            seededContent(summary = SummaryUiState.FailedPermanent("This video can't be summarized.")),
            TAB_SUMMARY,
        )

    @Test
    fun summary_quota_exhausted() = captureOnCream("summary_quota", seededContent(quotaExhausted = true), TAB_SUMMARY)

    // --- Edge states (Cream) ---
    @Test
    fun episodes_empty() = captureOnCream("episodes_empty", seededContent(episodes = emptyList()), TAB_EPISODES)

    @Test
    fun episodes_all_watched() =
        captureOnCream(
            "episodes_all_watched",
            seededContent(episodes = seededEpisodes().map { it.copy(watched = true, playing = false) }),
            TAB_EPISODES,
        )

    @Test
    fun notes_empty() = captureOnCream("notes_empty", seededContent(notes = emptyList()), TAB_NOTES)

    // --- Gap states (Cream) ---
    @Test
    fun loading() = captureOnCream("loading", PlaylistUiState.Loading, TAB_EPISODES)

    @Test
    fun error() = captureOnCream("error", PlaylistUiState.Error, TAB_EPISODES)

    private fun captureOnCream(
        key: String,
        state: PlaylistUiState,
        initialTab: Int,
    ) {
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) {
                PlaylistContent(
                    state = state,
                    onOpenPlayer = {},
                    onBack = {},
                    onRetry = {},
                    initialTab = initialTab,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/images/playlist_state_$key.png",
        )
        assertNoPurePixels(composeTestRule, "playlist_state/$key")
        assertRootBackground(composeTestRule, "playlist_state/$key", EditorialPalettes.Cream.paper)
    }

    private fun captureOnAllPalettes(
        key: String,
        state: PlaylistUiState,
        initialTab: Int,
    ) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)
        composeTestRule.setContent {
            EditorialTheme(palette = palette) {
                PlaylistContent(
                    state = state,
                    onOpenPlayer = {},
                    onBack = {},
                    onRetry = {},
                    initialTab = initialTab,
                )
            }
        }
        for (nextPalette in EditorialPalettes.All) {
            palette = nextPalette
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/snapshots/images/playlist_state_${key}_${nextPalette.key}.png",
            )
            assertNoPurePixels(composeTestRule, "playlist_state/$key/${nextPalette.key}")
            assertRootBackground(composeTestRule, "playlist_state/$key/${nextPalette.key}", nextPalette.paper)
        }
    }

    private fun seededEpisodes(): List<EpisodeEntry> =
        listOf(
            EpisodeEntry(1, "ed-v01", "Why design systems plateau", "12:08", watched = true, playing = false),
            EpisodeEntry(2, "ed-v02", "Tokens, but actually useful", "18:33", watched = false, playing = false),
            EpisodeEntry(3, "ed-v09", "The Future of Design Systems", "23:14", watched = false, playing = true),
            EpisodeEntry(4, "ed-v10", "When to fork the system", "15:47", watched = false, playing = false),
        )

    private fun seededContent(
        episodes: List<EpisodeEntry> = seededEpisodes(),
        summary: SummaryUiState =
            SummaryUiState.Completed(
                content =
                    "Design systems plateau when they're treated as a library instead of a contract. " +
                        "The next decade belongs to runtime systems — tokens that resolve at render time, " +
                        "components that negotiate with their host, and adoption measured in deleted code.",
                model = "free",
            ),
        quotaExhausted: Boolean = false,
        notes: List<NoteEntry> =
            listOf(
                NoteEntry("AT 3:42", "“400 components, nobody used them” — ask for source on the number."),
                NoteEntry("AT 12:47", "The 5-line contract idea. Try to draft this for the Button at work."),
            ),
    ): PlaylistUiState.Content =
        PlaylistUiState.Content(
            cover =
                Cover(
                    appBarKicker = "Vol. 01 · Design",
                    kicker = "A series in 14 videos",
                    title = "Designing Better Interfaces",
                    dek = "A season on contracts, runtime systems, and editorial maintenance.",
                    byline = "by Joey Banks · 4h 54m · last opened today",
                    continueLabel = "Continue · Ep 3",
                    continueVideoId = "ed-v09",
                    folioLeft = "Vol. 01 · Designing Better Interfaces",
                ),
            episodes = episodes,
            summary =
                SummaryTabState(
                    summary = summary,
                    quotaExhausted = quotaExhausted,
                    readFirst =
                        if (summary is SummaryUiState.Completed) {
                            "Begin with Episode 1 — “Why design systems plateau”."
                        } else {
                            null
                        },
                ),
            notes = notes,
            folioRight = "3 / 14",
        )

    private companion object {
        const val TAB_EPISODES = 0
        const val TAB_SUMMARY = 1
        const val TAB_NOTES = 2
    }
}
