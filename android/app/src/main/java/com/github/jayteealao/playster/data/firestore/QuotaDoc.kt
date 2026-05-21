package com.github.jayteealao.playster.data.firestore

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Kotlin mirror of `QuotaDocument` in backend/functions/src/models/index.ts.
 * `recentTimestamps` is a sliding 60s window of epoch-ms entries.
 * UI-state mapping ([QuotaState] / [toQuotaState]) lives in
 * `screens.common.state` to keep this DTO layer free of display policy.
 */
data class QuotaDoc(
    val date: String = "",
    val requestCount: Long = 0L,
    val dailyLimit: Long = 1000L,
    val perMinuteLimit: Long = 20L,
    val recentTimestamps: List<Long> = emptyList(),
)

fun DocumentSnapshot.toQuotaDoc(): QuotaDoc? {
    if (!exists()) return null
    @Suppress("UNCHECKED_CAST")
    val raw = (get("recentTimestamps") as? List<Any?>).orEmpty()
    val timestamps = raw.mapNotNull { entry ->
        when (entry) {
            is Number -> entry.toLong()
            else -> null
        }
    }
    return QuotaDoc(
        date = getString("date") ?: "",
        requestCount = getLong("requestCount") ?: 0L,
        dailyLimit = getLong("dailyLimit") ?: 1000L,
        perMinuteLimit = getLong("perMinuteLimit") ?: 20L,
        recentTimestamps = timestamps,
    )
}
