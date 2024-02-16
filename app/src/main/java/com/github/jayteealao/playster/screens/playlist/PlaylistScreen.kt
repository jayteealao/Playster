package com.github.jayteealao.playster.screens.playlist

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.jayteealao.playster.screens.auth.AuthViewModel
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun PlaylistScreen(authViewModel: AuthViewModel = hiltViewModel()) {

    val service = YouTube.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        authViewModel.userLogin.value.first
    )
        .setApplicationName("Playster")
        .build()

    val displayItems = remember {
        mutableStateListOf<Playlist>()
    }

    LaunchedEffect(true) {
        withContext(Dispatchers.IO) {
            val items = service.playlists().list(listOf("snippet", "contentDetails"))
                .setMaxResults(50L)
                .setMine(true)
                .execute().items
            displayItems.addAll(items)
        }
    }

    LazyColumn(
        verticalArrangement = spacedBy(8.dp)
    ) {
        items(
            items = displayItems.toList(),
            key = { it.id }
        ) {
//            Text(text = it)
            PlayCard(
                modifier = Modifier
//                    .height(200.dp)
                    .fillMaxWidth(),
                playlist = it,
            )
        }
    }

}


