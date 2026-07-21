package com.github.jayteealao.playster.data

import android.content.Context

/**
 * Release twin of the debug-only progress fault gate: a compiled no-op.
 * Release builds carry no fault-injection seam, no preference, and no
 * receiver — the debug source set owns all of it.
 */
object ProgressFaultGate {
    /**
     * Always false: shipped builds never force a write failure.
     *
     * sdlc-debt: UNUSED_PARAMETER suppression — the signature must mirror the
     * debug variant's (which reads a context-keyed pref); retire with the gate.
     */
    @Suppress("UNUSED_PARAMETER")
    fun consumeArmed(context: Context): Boolean = false
}
