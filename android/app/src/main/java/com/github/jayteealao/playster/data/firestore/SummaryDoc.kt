package com.github.jayteealao.playster.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Kotlin mirror of `SummaryDocument` in backend/functions/src/models/index.ts.
 * The Firestore SDK builds this via [DocumentSnapshot.toSummaryDoc]; field
 * defaults keep partially-written documents from blowing up the mapper.
 * Wire-format → UI-state mapping ([SummaryStatus] / [fromWire]) lives in
 * `screens.common.state` to keep this DTO layer free of display policy.
 */
data class SummaryDoc(
    @DocumentId val id: String = "",
    val videoId: String = "",
    val statusWire: String? = null,
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
    return SummaryDoc(
        id = id,
        videoId = getString("videoId") ?: id,
        statusWire = getString("status"),
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
