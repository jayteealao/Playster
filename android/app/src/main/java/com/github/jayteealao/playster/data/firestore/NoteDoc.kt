package com.github.jayteealao.playster.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Kotlin mirror of a `users/{uid}/notes/{autoId}` document (backend-schema
 * `NoteDocument`). All fields default so a partially-written document doesn't
 * crash the Firestore POJO mapper, the same convention the sibling DTOs follow.
 * The Playlist Notes tab is the first Android consumer; creation lands with the
 * player/transcript slices.
 */
data class NoteDoc(
    @DocumentId val id: String = "",
    val videoId: String = "",
    val playlistId: String = "",
    val t: Double = 0.0,
    val text: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

fun DocumentSnapshot.toNoteDoc(): NoteDoc? {
    if (!exists()) return null
    return NoteDoc(
        id = id,
        videoId = getString("videoId") ?: "",
        playlistId = getString("playlistId") ?: "",
        t = getDouble("t") ?: 0.0,
        text = getString("text") ?: "",
        createdAt = getTimestamp("createdAt"),
        updatedAt = getTimestamp("updatedAt"),
    )
}
