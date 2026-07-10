package com.github.jayteealao.playster.screens.videoDetail.summary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.screens.common.InProgressIndicator

/**
 * Collapsible transcript section rendered below the summary on SummaryScreen.
 *
 * All four pointer-doc states resolve to a definite piece of UI — no infinite
 * spinner with no resolution path. [TranscriptUiState.Loading] shows a spinner
 * with a label and resolves automatically when Firestore delivers the updated doc.
 *
 * Default state is collapsed; operator taps the header to expand.
 */
@Composable
fun TranscriptSection(
    state: TranscriptUiState,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("transcript-section"),
    ) {
        // Collapsible header — always visible regardless of state
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp)
                    .testTag("transcript-header"),
        ) {
            Text(
                text = if (expanded) "Transcript ▲" else "Transcript ▼",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
            ) {
                when (state) {
                    is TranscriptUiState.Loading -> {
                        // Spinner + label: not an infinite spinner — will resolve when
                        // the Firestore listener delivers the pointer doc update.
                        InProgressIndicator(
                            label = "Loading transcript…",
                            testTagValue = "transcript-loading-spinner",
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }

                    is TranscriptUiState.Available -> {
                        if (state.segments.isEmpty()) {
                            Text(
                                text = "Transcript has no segments.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .padding(vertical = 8.dp)
                                        .testTag("transcript-empty-segments"),
                            )
                        } else {
                            // Regular Column (not LazyColumn) because this section is nested
                            // inside a verticalScroll Column in SummaryScreenContent. A nested
                            // LazyColumn with unbounded height causes an infinite-measure crash.
                            // sdlc-debt: Column renders all segments eagerly; upgrade path is to
                            //   refactor SummaryScreenContent to a single LazyColumn so the
                            //   segment list can be lazily rendered.
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .testTag("transcript-segments-list"),
                            ) {
                                state.segments.forEach { segment ->
                                    SegmentRow(segment = segment)
                                }
                            }
                        }
                    }

                    is TranscriptUiState.Unavailable -> {
                        Text(
                            text = "No transcript available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(vertical = 8.dp)
                                    .testTag("transcript-unavailable"),
                        )
                    }

                    is TranscriptUiState.Error -> {
                        // Non-blocking: a single text line, no overlay, no dialog.
                        // Summary above is still readable and scrollable.
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier
                                    .padding(vertical = 8.dp)
                                    .testTag("transcript-error"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentRow(
    segment: TranscriptSegmentUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Text(
            text = formatTimestamp(segment.startSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(end = 8.dp)
                    .testTag("transcript-segment-timestamp"),
        )
        Text(
            text = segment.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("transcript-segment-text"),
        )
    }
}
