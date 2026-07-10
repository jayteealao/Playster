package com.github.jayteealao.playster.data.firestore

import com.google.firebase.firestore.DocumentId

/**
 * Kotlin mirror of `PlaylistDocument` in backend/functions/src/models/index.ts.
 *
 * All fields default to empty so a partially-written backend document doesn't
 * crash the Firestore POJO mapper.
 */
data class PlaylistDoc(
    @DocumentId val id: String = "",
    val title: String = "",
    val description: String = "",
    val thumbnailUrl: String = "",
    val videoCount: Long = 0L,
    val publishedAt: String = "",
    val channelTitle: String = "",
    val privacyStatus: String = "",
    val source: String = "api",
)
