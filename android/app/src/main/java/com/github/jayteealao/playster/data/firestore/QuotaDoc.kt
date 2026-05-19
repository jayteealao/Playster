package com.github.jayteealao.playster.data.firestore

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Kotlin mirror of `QuotaDocument` in backend/functions/src/models/index.ts.
 * `recentTimestamps` is a sliding 60s window of epoch-ms entries.
 */
data class QuotaDoc(
    val date: String = "",
    val requestCount: Long = 0L,
    val dailyLimit: Long = 1000L,
    val perMinuteLimit: Long = 20L,
    val recentTimestamps: List<Long> = emptyList(),
)

/**
 * Derived UI state from a [QuotaDoc] (or absence of one). Banner copy and CTA
 * enablement keys off the sealed variants — no rate-of-change logic, the
 * Firestore listener is the debounce.
 */
sealed interface QuotaState {
    data object Healthy : QuotaState
    data object DailyExhausted : QuotaState
    data object PerMinuteExhausted : QuotaState

    val isDisabled: Boolean
        get() = this !is Healthy
}

fun QuotaDoc?.toQuotaState(nowMillis: Long = System.currentTimeMillis()): QuotaState {
    if (this == null) return QuotaState.Healthy
    if (requestCount >= dailyLimit) return QuotaState.DailyExhausted
    val windowStart = nowMillis - 60_000L
    val active = recentTimestamps.count { it >= windowStart }
    if (active >= perMinuteLimit) return QuotaState.PerMinuteExhausted
    return QuotaState.Healthy
}

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
