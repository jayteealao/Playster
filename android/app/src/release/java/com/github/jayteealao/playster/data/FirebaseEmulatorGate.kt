package com.github.jayteealao.playster.data

import android.content.Context

/**
 * Release twin of the debug-only emulator gate: a compiled no-op. Release
 * builds carry no emulator wiring, no preference, and no emulator-redirect
 * call — the debug source set owns all of it.
 */
object FirebaseEmulatorGate {
    /**
     * No-op: shipped builds never talk to local emulators.
     *
     * sdlc-debt: UNUSED_PARAMETER suppression — the signature must mirror the
     * debug variant's (which reads a context-keyed pref); retire with the gate.
     */
    @Suppress("UNUSED_PARAMETER")
    fun configure(context: Context) = Unit
}
