package com.github.jayteealao.playster.data.firestore

import com.google.firebase.firestore.DocumentId

/**
 * Kotlin mirror of `VideoDocument` in backend/functions/src/models/index.ts.
 */
data class VideoDoc(
    @DocumentId val id: String = "",
    val videoId: String = "",
    val title: String = "",
    val channelTitle: String = "",
    val channelId: String = "",
    val duration: String = "",
    val thumbnailUrl: String = "",
    val publishedAt: String = "",
    val viewCount: Long = 0L,
    val position: Long = 0L,
    val addedAt: String = "",
)
