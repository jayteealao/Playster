package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.screens.player.playback.PlaybackError
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.github.jayteealao.playster.screens.player.playback.PlaybackState
import com.github.jayteealao.playster.screens.player.playback.ProgressWriteSink
import com.github.jayteealao.playster.screens.transcript.TranscriptContent
import com.github.jayteealao.playster.screens.transcript.TranscriptEmbed
import com.github.jayteealao.playster.screens.transcript.TranscriptHeader
import com.github.jayteealao.playster.screens.transcript.TranscriptParagraph
import com.github.jayteealao.playster.screens.transcript.TranscriptUiState
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
 * AC7 mechanical leg: the Transcript renders editorially — the article with the
 * highlighter span and the accent-ruled marginalia, the active line, the sealed
 * loading/unavailable/error states, the note composer, and the mini-embed's
 * editorial error surface — across palettes, and no rendered pixel is ever pure
 * #FFFFFF/#000000. Stateless [TranscriptContent] is the subject, so every state
 * renders without playback/Hilt/nav; the live Masthead-strip band is a WebView
 * (like the Player's video area) and is captured as a placeholder here, its live
 * pixel deferred to the on-device gate.
 *
 * Record: ./gradlew recordRoborazziDebug   Verify: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class TranscriptStateRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun article_allPalettes() = captureContentAllPalettes("article", available())

    @Test
    fun loading() = captureContentCream("loading", TranscriptUiState.Loading)

    @Test
    fun unavailable() = captureContentCream("unavailable", TranscriptUiState.Unavailable)

    @Test
    fun error() = captureContentCream("error", TranscriptUiState.Error("The transcript could not be reached."))

    @Test
    fun embedError() {
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) {
                TranscriptEmbed(
                    // Rendering-only: the error branch never touches this
                    // session (no controllerFor/YouTubePlayerHost call in
                    // this path), so the progress-write sink BC-1 added to
                    // PlaybackSession is never exercised here — a no-op
                    // satisfies the constructor without a live Firestore/
                    // FirebaseApp dependency this test has no other need for.
                    session = PlaybackSession(progressWriteSink = ProgressWriteSink { _, _, _, _, _ -> }),
                    playbackState = PlaybackState.Error(PlaybackError.EmbedDisabled),
                    positionLabel = "10:27",
                    onOpenPlayer = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/transcript_state_embed_error.png")
        assertNoPurePixels(composeTestRule, "transcript_state/embed_error")
    }

    private fun captureContentCream(
        key: String,
        state: TranscriptUiState,
    ) {
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) { Scaffold(state) }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/transcript_state_$key.png")
        assertNoPurePixels(composeTestRule, "transcript_state/$key")
        assertRootBackground(composeTestRule, "transcript_state/$key", EditorialPalettes.Cream.paper)
    }

    private fun captureContentAllPalettes(
        key: String,
        state: TranscriptUiState,
    ) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)
        composeTestRule.setContent {
            EditorialTheme(palette = palette) { Scaffold(state) }
        }
        for (next in EditorialPalettes.All) {
            palette = next
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/images/transcript_state_${key}_${next.key}.png")
            assertNoPurePixels(composeTestRule, "transcript_state/$key/${next.key}")
            assertRootBackground(composeTestRule, "transcript_state/$key/${next.key}", next.paper)
        }
    }

    @Composable
    private fun Scaffold(state: TranscriptUiState) {
        val position = remember { mutableFloatStateOf(30f) }
        TranscriptContent(
            state = state,
            header =
                TranscriptHeader(
                    kicker = "Ep. 9 · The machines that learned to read us",
                    byline = "A conversation with Joey Banks · Nov 2025",
                ),
            position = position,
            playing = true,
            onBack = {},
            onOpenPlayer = {},
            onSeek = {},
            onToggleHighlight = {},
            onPlayPause = {},
            onCreateNote = {},
            embedSlot = {
                // The live band is a WebView; a warm placeholder stands in for the golden.
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF15130F)),
                )
            },
        )
    }

    private fun available(): TranscriptUiState.Available =
        TranscriptUiState.Available(
            paragraphs =
                listOf(
                    TranscriptParagraph(
                        0.0,
                        "0:00",
                        "The feed is not neutral furniture. Every ordering is an argument about what deserves your attention next.",
                        highlighted = false,
                    ),
                    TranscriptParagraph(
                        12.0,
                        "0:12",
                        "A reader underlines to disagree with forgetting.",
                        highlighted = true,
                        note = "Come back to this — the whole thesis is here.",
                    ),
                    TranscriptParagraph(
                        30.0,
                        "0:30",
                        "So the question is not whether the machine can read. It is whether we still remember how to read against it.",
                        highlighted = false,
                    ),
                    TranscriptParagraph(41.0, "0:41", "Attention is the only currency the reader actually owns.", highlighted = false),
                ),
            following = true,
        )
}
