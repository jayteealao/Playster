package com.github.jayteealao.playster.screens.videoDetail.summary

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Renders [SummaryScreenContent] in each of its four primary states and
 * asserts the discriminating test tag is present. Exercises only the
 * stateless half — the ViewModel is not involved.
 */
class SummaryScreenComposeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inProgress_rendersSpinner() {
        composeTestRule.setContent {
            SummaryScreenContent(
                state = SummaryUiState.InProgress,
                transcriptState = TranscriptUiState.Loading,
                onRetry = {},
                onSummarize = {},
            )
        }
        composeTestRule.onNodeWithTag("summary-in-progress").assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary-in-progress-spinner").assertIsDisplayed()
    }

    @Test
    fun completed_rendersMarkdownAndModel() {
        composeTestRule.setContent {
            SummaryScreenContent(
                state =
                    SummaryUiState.Completed(
                        content = "# Hello\n\nBody",
                        model = "free",
                    ),
                transcriptState = TranscriptUiState.Loading,
                onRetry = {},
                onSummarize = {},
            )
        }
        composeTestRule.onNodeWithTag("summary-completed").assertIsDisplayed()
    }

    @Test
    fun failedTransient_rendersRetryButton() {
        composeTestRule.setContent {
            SummaryScreenContent(
                state = SummaryUiState.FailedTransient(message = "boom"),
                transcriptState = TranscriptUiState.Loading,
                onRetry = {},
                onSummarize = {},
            )
        }
        composeTestRule.onNodeWithTag("summary-failed-transient").assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary-retry-button").assertIsDisplayed()
    }

    @Test
    fun failedPermanent_doesNotRenderRetry() {
        composeTestRule.setContent {
            SummaryScreenContent(
                state = SummaryUiState.FailedPermanent(message = "no can do"),
                transcriptState = TranscriptUiState.Loading,
                onRetry = {},
                onSummarize = {},
            )
        }
        composeTestRule.onNodeWithTag("summary-failed-permanent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary-error-icon").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithTag("summary-retry-button")
            .assertCountEquals(0)
    }

    // M-10: cold-start NoSummary state — Summarize CTA is visible and its
    // click invokes the onSummarize callback.
    @Test
    fun noSummary_rendersSummarizeCta_andClickInvokesCallback() {
        var callbackInvoked = false
        composeTestRule.setContent {
            SummaryScreenContent(
                state = SummaryUiState.NoSummary,
                transcriptState = TranscriptUiState.Loading,
                onRetry = {},
                onSummarize = { callbackInvoked = true },
            )
        }
        composeTestRule.onNodeWithTag("summary-summarize-button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary-summarize-button").performClick()
        assert(callbackInvoked) { "onSummarize callback was not invoked after clicking the button" }
    }
}
