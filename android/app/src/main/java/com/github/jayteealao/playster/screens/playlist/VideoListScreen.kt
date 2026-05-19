package com.github.jayteealao.playster.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.QuotaState
import com.github.jayteealao.playster.data.firestore.SummaryRepository
import com.github.jayteealao.playster.data.firestore.SummaryStatus
import com.github.jayteealao.playster.data.firestore.VideoDoc
import com.github.jayteealao.playster.screens.common.rememberQuotaState
import com.github.jayteealao.playster.ui.theme.Gray50
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class VideoListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    firestoreRepository: FirestoreRepository,
    private val summaryRepository: SummaryRepository,
) : ViewModel() {

    val playlistId: String = savedStateHandle["playlistId"] ?: ""

    val videos: StateFlow<List<VideoDoc>> = firestoreRepository
        .videosFlow(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Inspect `summaries/{videoId}` once and resolve whether the navigation
     * should auto-dispatch a fresh summary request. Doc absent or failed →
     * autoDispatch=true; queued/pending/running/completed → false (the
     * SummaryScreen will render the current state from the listener).
     */
    fun onSummarizeClick(videoId: String, onNavigate: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            val existing = summaryRepository.getOnce(videoId)
            val autoDispatch = when (existing?.status) {
                null,
                SummaryStatus.UNKNOWN,
                SummaryStatus.FAILED_TRANSIENT,
                SummaryStatus.FAILED_PERMANENT -> true
                else -> false
            }
            onNavigate(videoId, autoDispatch)
        }
    }
}

@Composable
fun VideoListScreen(
    onBack: () -> Unit = {},
    onOpenVideo: (videoId: String, autoDispatch: Boolean) -> Unit = { _, _ -> },
    viewModel: VideoListViewModel = hiltViewModel(),
) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val quotaState = rememberQuotaState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable { onBack() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "← Playlists",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (videos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No videos yet — pull-to-refresh on the playlists screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = videos, key = { it.videoId.ifBlank { it.id } }) { video ->
                    VideoRow(
                        video = video,
                        quotaState = quotaState,
                        onOpen = { onOpenVideo(video.videoId, false) },
                        onSummarize = {
                            viewModel.onSummarizeClick(video.videoId, onOpenVideo)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoRow(
    video: VideoDoc,
    quotaState: QuotaState,
    onOpen: () -> Unit,
    onSummarize: () -> Unit,
) {
    val summarizeEnabled = quotaState is QuotaState.Healthy
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Gray50),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = video.thumbnailUrl.takeIf { it.isNotBlank() },
                contentDescription = "video thumbnail",
                modifier = Modifier
                    .size(width = 96.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = video.channelTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onSummarize,
                enabled = summarizeEnabled,
                modifier = Modifier.testTag(
                    if (summarizeEnabled) "summarize-button-enabled" else "summarize-button-disabled",
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = if (summarizeEnabled) {
                        "Summarize this video"
                    } else {
                        "Daily summary limit reached — try again after midnight UTC."
                    },
                )
            }
        }
    }
}
