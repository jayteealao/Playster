package com.github.jayteealao.playster.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot

enum class SummaryStatus {
    QUEUED,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED_TRANSIENT,
    FAILED_PERMANENT,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): SummaryStatus = when (value) {
            "queued" -> QUEUED
            "pending" -> PENDING
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed-transient" -> FAILED_TRANSIENT
            "failed-permanent" -> FAILED_PERMANENT
            else -> UNKNOWN
        }
    }
}

/**
 * Kotlin mirror of `SummaryDocument` in backend/functions/src/models/index.ts.
 * The Firestore SDK builds this via [DocumentSnapshot.toSummaryDoc]; field
 * defaults keep partially-written documents from blowing up the mapper.
 */
data class SummaryDoc(
    @DocumentId val id: String = "",
    val videoId: String = "",
    val status: SummaryStatus = SummaryStatus.UNKNOWN,
    val model: String? = null,
    val content: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val summarizerJobId: String? = null,
    val requestedAt: Timestamp? = null,
    val dispatchedAt: Timestamp? = null,
    val completedAt: Timestamp? = null,
)

fun DocumentSnapshot.toSummaryDoc(): SummaryDoc? {
    if (!exists()) return null
    val statusWire = getString("status")
    return SummaryDoc(
        id = id,
        videoId = getString("videoId") ?: id,
        status = SummaryStatus.fromWire(statusWire),
        model = getString("model"),
        content = getString("content"),
        errorCode = getString("errorCode"),
        errorMessage = getString("errorMessage"),
        summarizerJobId = getString("summarizerJobId"),
        requestedAt = getTimestamp("requestedAt"),
        dispatchedAt = getTimestamp("dispatchedAt"),
        completedAt = getTimestamp("completedAt"),
    )
}
