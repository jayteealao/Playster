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
fun SummaryScreen(
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SummaryScreenContent(
        state = state,
        onRetry = viewModel::retry,
        onSummarize = viewModel::retry,
    )
}

@Composable
fun SummaryScreenContent(
    state: SummaryUiState,
    onRetry: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SummaryUiState.InProgress -> Column(
            modifier = modifier
                .fillMaxSize()
                .testTag("summary-in-progress"),
        ) {
            InProgressIndicator(label = "Generating summary…")
        }

        is SummaryUiState.Completed -> Column(
            modifier = modifier
                .fillMaxSize()
                .testTag("summary-completed")
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
        }

        is SummaryUiState.FailedTransient -> Column(
            modifier = modifier
                .fillMaxSize()
                .testTag("summary-failed-transient"),
        ) {
            ErrorPanel(message = state.message, onRetry = onRetry)
        }

        is SummaryUiState.FailedPermanent -> Column(
            modifier = modifier
                .fillMaxSize()
                .testTag("summary-failed-permanent"),
        ) {
            ErrorPanel(message = state.message, onRetry = null)
        }

        is SummaryUiState.NoSummary -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp)
                .testTag("summary-no-summary"),
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
                modifier = Modifier
                    .padding(top = 16.dp)
                    .testTag("summary-summarize-button"),
            ) {
                Text(text = "Summarize this video")
            }
        }
    }
}
