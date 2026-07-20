package com.github.jayteealao.playster.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Kotlin mirror of a `users/{uid}/highlights/{id}` document (backend-schema
 * `HighlightDocument`): the reader's underline on one transcript line. All
 * fields default so a partially-written document never crashes the mapper —
 * the same convention the sibling DTOs ([NoteDoc], [ProgressDoc]) follow.
 *
 * [segmentStart] is the transcript segment's offset in seconds; it is the
 * stable anchor the highlight rides (matching the deployed
 * `highlights(videoId ASC, segmentStart ASC)` composite index), so a re-fetched
 * transcript with the same segmentation re-renders the highlight in place.
 */
data class HighlightDoc(
    @DocumentId val id: String = "",
    val videoId: String = "",
    val segmentStart: Double = 0.0,
    val text: String = "",
    val createdAt: Timestamp? = null,
)

fun DocumentSnapshot.toHighlightDoc(): HighlightDoc? {
    if (!exists()) return null
    return HighlightDoc(
        id = id,
        videoId = getString("videoId") ?: "",
        segmentStart = getDouble("segmentStart") ?: 0.0,
        text = getString("text") ?: "",
        // ESTIMATE (not the SDK default NONE): a just-written, not-yet-acked
        // `createdAt` serverTimestamp() would otherwise deserialize null and
        // silently drop out of the "highlights this week" stat (CR-4) until the
        // server ack round-trips.
        createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
    )
}
