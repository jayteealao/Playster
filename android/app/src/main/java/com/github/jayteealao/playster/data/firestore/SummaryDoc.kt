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
    val chapters: List<SummaryChapter> = emptyList(),
    val requestedAt: Timestamp? = null,
    val dispatchedAt: Timestamp? = null,
    val completedAt: Timestamp? = null,
)

/**
 * Kotlin mirror of `SummaryChapter` (backend/functions/src/models/index.ts) —
 * canonical units are seconds; `dur` is null for the final chapter when no
 * transcript bounded it. Additive read of a field the summarizer already
 * writes; a summary produced before chapters landed simply carries an empty
 * list.
 */
data class SummaryChapter(
    val t: Double = 0.0,
    val label: String = "",
    val dur: Double? = null,
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
        chapters = readChapters(),
        requestedAt = getTimestamp("requestedAt"),
        dispatchedAt = getTimestamp("dispatchedAt"),
        completedAt = getTimestamp("completedAt"),
    )
}

@Suppress("UNCHECKED_CAST")
private fun DocumentSnapshot.readChapters(): List<SummaryChapter> {
    val raw = get("chapters") as? List<Map<String, Any?>> ?: return emptyList()
    return raw.mapNotNull { entry ->
        val label = entry["label"] as? String ?: return@mapNotNull null
        val t = (entry["t"] as? Number)?.toDouble() ?: return@mapNotNull null
        SummaryChapter(t = t, label = label, dur = (entry["dur"] as? Number)?.toDouble())
    }
}
