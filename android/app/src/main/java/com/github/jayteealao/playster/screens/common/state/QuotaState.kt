package com.github.jayteealao.playster.screens.common.state

import com.github.jayteealao.playster.data.firestore.QuotaDoc

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
    val windowStart = nowMillis - OPENROUTER_WINDOW_MS
    val active = recentTimestamps.count { it >= windowStart }
    if (active >= perMinuteLimit) return QuotaState.PerMinuteExhausted
    return QuotaState.Healthy
}
