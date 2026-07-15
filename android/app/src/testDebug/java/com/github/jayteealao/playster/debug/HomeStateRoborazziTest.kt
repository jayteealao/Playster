package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.screens.home.Headliner
import com.github.jayteealao.playster.screens.home.HomeContent
import com.github.jayteealao.playster.screens.home.HomeUiState
import com.github.jayteealao.playster.screens.home.Masthead
import com.github.jayteealao.playster.screens.home.ShelfEntry
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
 * AC4: the Home gap states render editorially — never a Material spinner, never
 * a blank screen — in all four palettes, plus a seeded content regression. The
 * stateless [HomeContent] is the subject, so every state renders with no Hilt,
 * Firestore, or navigation in the graph (4 states × 4 palettes = 16 JVM goldens
 * at 412×892, Source face / size M), and no rendered pixel is ever pure
 * #FFFFFF/#000000.
 *
 * Record baselines: ./gradlew recordRoborazziDebug
 * Verify against them: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class HomeStateRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun empty_allPalettes() = captureStateOnAllPalettes("empty", HomeUiState.Empty)

    @Test
    fun loading_allPalettes() = captureStateOnAllPalettes("loading", HomeUiState.Loading)

    @Test
    fun error_allPalettes() = captureStateOnAllPalettes("error", HomeUiState.Error)

    @Test
    fun content_allPalettes() = captureStateOnAllPalettes("content", sampleContent())

    private fun captureStateOnAllPalettes(
        stateKey: String,
        state: HomeUiState,
    ) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)

        composeTestRule.setContent {
            EditorialTheme(palette = palette) {
                HomeContent(
                    state = state,
                    onOpenPlaylist = {},
                    onOpenPlayer = {},
                    onOpenSearch = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        for (nextPalette in EditorialPalettes.All) {
            palette = nextPalette
            composeTestRule.waitForIdle()

            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/snapshots/images/home_state_${stateKey}_${nextPalette.key}.png",
            )
            assertNoPurePixels(composeTestRule, "home_state/$stateKey/${nextPalette.key}")
        }
    }

    private fun sampleContent(): HomeUiState.Content =
        HomeUiState.Content(
            masthead =
                Masthead(
                    issueLine = "Issue 38 · Tuesday",
                    unreadLabel = "43h unread",
                    titleTop = "Today's",
                    titleEmphasis = "shelf.",
                    deck = "Four playlists you started, one you haven't. A few minutes each.",
                ),
            headliner =
                Headliner(
                    videoId = "ed-v09",
                    episodeLabel = "Continue · Episode 9",
                    title = "The Future of Design Systems",
                    meta = "with Joey Banks · 10:27 remaining",
                    positionLabel = "12:47",
                    durationLabel = "23:14",
                    progress = 0.55f,
                ),
            shelf =
                listOf(
                    ShelfEntry("ed-p1", 1, "Design · Vol. 01", "Designing Better Interfaces", "by Joey Banks · 14 videos · 4h 54m", 0.59f),
                    ShelfEntry("ed-p2", 2, "Typography · Vol. 02", "Modern Type Practice", "by Ellen Lupton · 12 videos · 4h 12m", null),
                    ShelfEntry("ed-p5", 3, "Inbox · Vol. 05", "Inbox", "by Saved for later · 3 videos · 1h 03m", null),
                ),
        )
}
