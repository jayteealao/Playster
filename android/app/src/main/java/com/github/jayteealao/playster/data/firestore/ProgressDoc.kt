package com.github.jayteealao.playster.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/**
 * Kotlin mirror of a `users/{uid}/progress/{id}` document (backend-schema).
 *
 * The collection carries a `kind` discriminator: `kind == "playlist"` docs are
 * keyed by playlistId and track `lastOpenedAt` (the shelf ordering key);
 * `kind == "video"` docs are keyed by videoId and track
 * `positionSeconds`/`durationSeconds` (the resume position). All fields default
 * so a partially-written document doesn't crash the Firestore POJO mapper, the
 * same convention [PlaylistDoc] follows.
 */
data class ProgressDoc(
    @DocumentId val id: String = "",
    val kind: String = "",
    val videoId: String = "",
    val playlistId: String = "",
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val lastOpenedAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
