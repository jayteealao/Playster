package com.github.jayteealao.playster.screens.common.state

// Keep in sync with backend/functions/src/summarizer/constants.ts — values are
// server-owned; this file is a local mirror for UI heuristics only.

/** Maximum summary requests per calendar day (UTC). */
const OPENROUTER_DAILY_LIMIT: Int = 1_000

/** Maximum summary requests per rolling 60-second window. */
const OPENROUTER_PER_MINUTE_LIMIT: Int = 20

/** Rolling window duration in milliseconds (matches per-minute limit). */
const OPENROUTER_WINDOW_MS: Long = 60_000L
