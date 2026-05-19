package com.github.jayteealao.playster.screens.videoDetail.summary

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
                state = SummaryUiState.Completed(
                    content = "# Hello\n\nBody",
                    model = "free",
                ),
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
}
