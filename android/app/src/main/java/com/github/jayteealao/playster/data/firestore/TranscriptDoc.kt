package com.github.jayteealao.playster.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Kotlin mirror of `TranscriptDocument` in backend/functions/src/models/index.ts.
 * Built by [DocumentSnapshot.toTranscriptDoc]; field defaults keep partially-written
 * documents from blowing up the mapper.
 *
 * Segments are stored in Firestore as a List<Map<String, Any>>. The mapper extracts
 * them into typed [TranscriptSegment] instances. A missing or malformed segment map
 * is silently dropped — the transcript renders with whatever segments survive, rather
 * than crashing the screen.
 */
data class TranscriptDoc(
    @DocumentId val id: String = "",
    val videoId: String = "",
    val statusWire: String? = null,
    val source: String? = null,
    val language: String? = null,
    /** Raw Firestore array of segment maps — each map has "start" (Double) and "text" (String). */
    val segments: List<TranscriptSegment> = emptyList(),
    val gcsPath: String? = null,
    val signedUrl: String? = null,
    val signedUrlExpiresAt: Timestamp? = null,
    val errorCode: String? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

/**
 * One transcript segment as stored by the backend. [start] is offset in seconds
 * from the video start; [text] is the spoken words for that segment.
 */
data class TranscriptSegment(
    val start: Double = 0.0,
    val text: String = "",
)

fun DocumentSnapshot.toTranscriptDoc(): TranscriptDoc? {
    if (!exists()) return null

    // Firestore stores segments as List<Map<String, Any>>. Extract them safely.
    @Suppress("UNCHECKED_CAST")
    val rawSegments = get("segments") as? List<Map<String, Any>> ?: emptyList()
    val segments = rawSegments.mapNotNull { map ->
        val start = (map["start"] as? Number)?.toDouble() ?: return@mapNotNull null
        val text = map["text"] as? String ?: return@mapNotNull null
        TranscriptSegment(start = start, text = text)
    }

    return TranscriptDoc(
        id = id,
        videoId = getString("videoId") ?: id,
        statusWire = getString("status"),
        source = getString("source"),
        language = getString("language"),
        segments = segments,
        gcsPath = getString("gcsPath"),
        signedUrl = getString("signedUrl"),
        signedUrlExpiresAt = getTimestamp("signedUrlExpiresAt"),
        errorCode = getString("errorCode"),
        createdAt = getTimestamp("createdAt"),
        updatedAt = getTimestamp("updatedAt"),
    )
}
