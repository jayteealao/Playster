package com.github.jayteealao.playster.screens.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.api.services.youtube.model.Playlist

@Composable
fun PlayCard(
    modifier: Modifier = Modifier,
    playlist: Playlist
) {
    Column(
        modifier = modifier,
    ) {
        AsyncImage(
            model = playlist.snippet.thumbnails.maxres?.url ?: playlist.snippet.thumbnails.high.url,
            contentDescription = "playlist image",
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, 8.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.FillBounds
            )

        Row(
        ) {

            Column(
                modifier = Modifier
            ) {

                Text(
                    text = playlist.snippet.title,
                    color = Color.Black,
                )

                Text(
                    text = playlist.snippet.channelTitle,
                    color = Color.Gray,
                )
            }

            Text(text = playlist.contentDetails.itemCount.toString(), modifier = Modifier.padding(8.dp))
        }
    }
}