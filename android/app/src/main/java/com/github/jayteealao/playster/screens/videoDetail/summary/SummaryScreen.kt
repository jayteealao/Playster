package com.github.jayteealao.playster.screens.videoDetail.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jayteealao.playster.screens.common.ErrorPanel
import com.github.jayteealao.playster.screens.common.InProgressIndicator
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun SummaryScreen(viewModel: SummaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val transcriptState by viewModel.transcriptUiState.collectAsStateWithLifecycle()
    SummaryScreenContent(
        state = state,
        transcriptState = transcriptState,
        onRetry = viewModel::retry,
        onSummarize = viewModel::retry,
    )
}

private fun stateTag(state: SummaryUiState): String =
    when (state) {
        is SummaryUiState.InProgress -> "summary-in-progress"
        is SummaryUiState.Completed -> "summary-completed"
        is SummaryUiState.FailedTransient -> "summary-failed-transient"
        is SummaryUiState.FailedPermanent -> "summary-failed-permanent"
        is SummaryUiState.NoSummary -> "summary-no-summary"
    }

@Composable
fun SummaryScreenContent(
    state: SummaryUiState,
    transcriptState: TranscriptUiState = TranscriptUiState.Loading,
    onRetry: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(stateTag(state)),
    ) {
        when (state) {
            is SummaryUiState.InProgress -> {
                InProgressIndicator(label = "Generating summary…")
            }

            is SummaryUiState.Completed -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                ) {
                    MarkdownText(
                        markdown = state.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Model: ${state.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TranscriptSection(state = transcriptState)
                }
            }

            is SummaryUiState.FailedTransient -> {
                ErrorPanel(message = state.message, onRetry = onRetry)
            }

            is SummaryUiState.FailedPermanent -> {
                ErrorPanel(message = state.message, onRetry = null)
            }

            is SummaryUiState.NoSummary -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No summary yet for this video.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onSummarize,
                        modifier =
                            Modifier
                                .padding(top = 16.dp)
                                .testTag("summary-summarize-button"),
                    ) {
                        Text(text = "Summarize this video")
                    }
                }
            }
        }
    }
}
