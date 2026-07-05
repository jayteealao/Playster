package com.github.jayteealao.playster.screens.videoDetail.summary

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class TranscriptSectionStateTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingState_rendersSpinnerWithLabel() {
        composeTestRule.setContent {
            TranscriptSection(state = TranscriptUiState.Loading)
        }
        // Click the header to expand
        composeTestRule.onNodeWithTag("transcript-header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("transcript-header").performClick()
        composeTestRule.onNodeWithTag("transcript-loading-spinner").assertIsDisplayed()
    }

    @Test
    fun availableState_rendersSegments() {
        val segments = listOf(
            TranscriptSegmentUi(startSeconds = 5.0, text = "Hello and welcome"),
            TranscriptSegmentUi(startSeconds = 10.5, text = "To this video"),
        )
        composeTestRule.setContent {
            TranscriptSection(state = TranscriptUiState.Available(
                segments = segments,
                language = "en",
                signedUrl = null,
            ))
        }
        composeTestRule.onNodeWithTag("transcript-header").performClick()
        composeTestRule.onNodeWithTag("transcript-segments-list").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello and welcome").assertIsDisplayed()
    }

    @Test
    fun unavailableState_rendersNoTranscriptText() {
        composeTestRule.setContent {
            TranscriptSection(state = TranscriptUiState.Unavailable)
        }
        composeTestRule.onNodeWithTag("transcript-header").performClick()
        composeTestRule.onNodeWithTag("transcript-unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("No transcript available").assertIsDisplayed()
    }

    @Test
    fun errorState_rendersErrorMessage() {
        composeTestRule.setContent {
            TranscriptSection(state = TranscriptUiState.Error("Transcript unavailable (NETWORK_ERROR)."))
        }
        composeTestRule.onNodeWithTag("transcript-header").performClick()
        composeTestRule.onNodeWithTag("transcript-error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transcript unavailable (NETWORK_ERROR).").assertIsDisplayed()
    }
}
