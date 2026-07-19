package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.screens.search.PlaylistResult
import com.github.jayteealao.playster.screens.search.SearchContent
import com.github.jayteealao.playster.screens.search.SearchUiState
import com.github.jayteealao.playster.screens.search.TranscriptResult
import com.github.jayteealao.playster.screens.search.TranscriptSearchState
import com.github.jayteealao.playster.screens.search.VideoResult
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
 * AC6 mechanical leg (the device-free pixel proxy) + AC3/AC5 sealed surfaces: the
 * hybrid Search renders editorially — the italic-serif field, the grouped
 * transcript/video/playlist results with the accent-ruled snippet + jump-to-T,
 * the filtered-empty that names the query, and the transcript-group error that
 * degrades that group alone — across all four palettes, no pixel ever pure
 * #FFFFFF/#000000. Stateless [SearchContent] is the subject, so every state
 * renders without a ViewModel, Hilt, or the network.
 *
 * Record: ./gradlew recordRoborazziDebug   Verify: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class SearchStateRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun results_allPalettes() = captureAllPalettes("results", resultsState())

    @Test
    fun filteredEmpty() = captureCream("filtered_empty", filteredEmptyState())

    @Test
    fun transcriptError() = captureCream("transcript_error", transcriptErrorState())

    private fun captureCream(
        key: String,
        state: SearchUiState,
    ) {
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) { Content(state) }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/search_state_$key.png")
        assertNoPurePixels(composeTestRule, "search_state/$key")
    }

    private fun captureAllPalettes(
        key: String,
        state: SearchUiState,
    ) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)
        composeTestRule.setContent {
            EditorialTheme(palette = palette) { Content(state) }
        }
        for (next in EditorialPalettes.All) {
            palette = next
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/search_state_${key}_${next.key}.png")
            assertNoPurePixels(composeTestRule, "search_state/$key/${next.key}")
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content(state: SearchUiState) {
        SearchContent(
            state = state,
            onQueryChange = {},
            onClearQuery = {},
            onRecentTap = {},
            onOpenTranscriptAt = { _, _ -> },
            onOpenPlayer = {},
            onOpenPlaylist = {},
        )
    }

    private val recents = listOf("design tokens", "figma variants", "andy allen", "a11y")

    private fun resultsState(): SearchUiState =
        SearchUiState(
            query = "design tokens",
            transcript =
                TranscriptSearchState.Results(
                    listOf(
                        TranscriptResult(
                            videoId = "v1",
                            title = "The Future of Design Systems",
                            snippet = "tokens stop being a paint bucket and start being the API your product reads from…",
                            jumpLabel = "JUMP TO 1:18",
                            startSeconds = 78.0,
                        ),
                    ),
                ),
            videos =
                listOf(
                    VideoResult("v2", "Tokens, but actually useful", "by Andy Allen · 18:33 · in Designing Better Interfaces"),
                ),
            playlists =
                listOf(
                    PlaylistResult("p2", "Modern Type Practice", "12 videos · Where type meets the screen."),
                ),
            recents = recents,
        )

    private fun filteredEmptyState(): SearchUiState =
        SearchUiState.Initial.copy(
            query = "quixotic",
            transcript = TranscriptSearchState.Empty,
            recents = recents,
        )

    private fun transcriptErrorState(): SearchUiState =
        SearchUiState(
            query = "design tokens",
            transcript = TranscriptSearchState.Error("Transcript search is unavailable right now. Title results are still shown."),
            videos = listOf(VideoResult("v2", "Tokens, but actually useful", "by Andy Allen · 18:33 · in Designing Better Interfaces")),
            playlists = emptyList(),
            recents = recents,
        )
}
