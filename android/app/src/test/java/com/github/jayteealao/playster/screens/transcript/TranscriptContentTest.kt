package com.github.jayteealao.playster.screens.transcript

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * CR-8 regression: malformed/low-quality-ASR upstream data can hand the
 * Transcript screen two paragraphs sharing the same `segmentStart` — nothing
 * upstream (backend, [TranscriptStateAssembler]) validates uniqueness. Before
 * the fix, `items(paragraphs, key = { it.segmentStart })` crashed the
 * LazyColumn with `IllegalArgumentException: Key ... was already used`. The
 * fix keys each row by an index-qualified composite instead, so duplicate
 * timestamps compose safely and every row still renders in order.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class TranscriptContentTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun duplicateSegmentStart_rendersBothRows_withoutCrashing() {
        val duplicateStart = 12.0
        val paragraphs =
            listOf(
                TranscriptParagraph(
                    duplicateStart,
                    "0:12",
                    "First line at the duplicate mark.",
                    highlighted = false,
                ),
                TranscriptParagraph(
                    duplicateStart,
                    "0:12",
                    "Second line at the same duplicate mark.",
                    highlighted = false,
                ),
            )

        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream) {
                val position = remember { mutableFloatStateOf(0f) }
                TranscriptContent(
                    state = TranscriptUiState.Available(paragraphs = paragraphs),
                    header = null,
                    position = position,
                    playing = false,
                    onBack = {},
                    onOpenPlayer = {},
                    onSeek = {},
                    onToggleHighlight = {},
                    onPlayPause = {},
                    onCreateNote = {},
                    embedSlot = { Box {} },
                )
            }
        }
        composeTestRule.waitForIdle()

        // No crash reaching this point is the primary assertion; both rows —
        // sharing a `segmentStart` — must still each render distinctly.
        composeTestRule.onNodeWithText("First line at the duplicate mark.").assertExists()
        composeTestRule.onNodeWithText("Second line at the same duplicate mark.").assertExists()
    }
}
