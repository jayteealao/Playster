package com.github.jayteealao.playster.screens.player.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * The wall-clock timeout [isDeviceOffline] alone can't give a Firestore
 * listener: `addSnapshotListener` only calls its error callback on a genuine
 * fault, never on the "no local cache, no server response" silence a
 * cold-start-offline launch produces (REL-5/6/7) — so a `combine`/`stateIn`
 * pipeline built on it sits on its initial `Loading` value forever, with no
 * offline banner, no timeout, no retry. The Player's own load path already
 * solves the analogous embed-side gap with a watchdog
 * ([PlaybackController.onLoadTimedOut]); this is the same idea for a plain
 * data [Flow].
 *
 * If [this] hasn't produced its first value within [timeoutMs] AND [isOffline]
 * reports true at that instant, emits [fallback]'s result once as a synthetic
 * first value. This never touches the online timeline: the watchdog only fires
 * when nothing has arrived by the deadline, so a normal (even slow) online
 * load is unaffected, and any later real value from [this] — a reconnect, a
 * cache warming up — still flows through untouched, since [fallback] is never
 * evaluated again once the source has produced a value.
 */
fun <T> Flow<T>.withOfflineFallback(
    isOffline: () -> Boolean,
    timeoutMs: Long = OFFLINE_GATE_TIMEOUT_MS,
    fallback: () -> T,
): Flow<T> =
    channelFlow {
        var firstArrived = false
        val watchdog =
            launch {
                delay(timeoutMs)
                if (!firstArrived && isOffline()) {
                    send(fallback())
                }
            }
        collect { value ->
            firstArrived = true
            watchdog.cancel()
            send(value)
        }
    }

/** How long a listener flow gets before an offline-with-no-first-value verdict is drawn. */
const val OFFLINE_GATE_TIMEOUT_MS = 4_000L
