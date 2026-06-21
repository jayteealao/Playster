package com.github.jayteealao.playster.screens.common.state

/**
 * Wire-format → UI-state mapping for summary status strings.
 * Lives in the UI layer because the mapping is display policy, not storage policy.
 */
enum class SummaryStatus {
    QUEUED,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED_TRANSIENT,
    FAILED_PERMANENT,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): SummaryStatus =
            when (value) {
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
