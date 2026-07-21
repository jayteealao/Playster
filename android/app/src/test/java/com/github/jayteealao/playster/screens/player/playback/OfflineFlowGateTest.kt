package com.github.jayteealao.playster.screens.player.playback

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * REL-5/6/7's shared fix: a Firestore listener flow that silently never emits
 * offline (no cache, no server ack) must resolve to a fallback rather than
 * hang [Home/Search/Transcript]ViewModel's `uiState` on Loading forever — but
 * only when offline, and only once nothing has arrived within the deadline,
 * so the ordinary (even slow) online load is never touched. Uses small
 * `timeoutMs` overrides + real `delay` (no virtual-time test dispatcher is
 * wired into this module) to keep the suite fast.
 */
class OfflineFlowGateTest {
    @Test
    fun sourceEmitsBeforeDeadline_passesThroughUntouched_evenWhileOffline() =
        runBlocking {
            val result =
                flow { emit("real") }
                    .withOfflineFallback(isOffline = { true }, timeoutMs = 50L, fallback = { "offline" })
                    .first()
            assertEquals("real", result)
        }

    @Test
    fun offlineWithNothingArrived_pastDeadline_emitsFallback() =
        runBlocking {
            val neverEmits = flow<String> { delay(10_000) }
            val result =
                neverEmits
                    .withOfflineFallback(isOffline = { true }, timeoutMs = 20L, fallback = { "offline" })
                    .first()
            assertEquals("offline", result)
        }

    @Test
    fun onlineWithNothingArrivedYet_neverFallsBack() =
        runBlocking {
            val neverEmits = flow<String> { delay(10_000) }
            val result =
                withTimeoutOrNull(80L) {
                    neverEmits
                        .withOfflineFallback(isOffline = { false }, timeoutMs = 20L, fallback = { "offline" })
                        .first()
                }
            assertNull(result)
        }

    @Test
    fun aLateRealValue_stillFlowsThrough_afterTheFallbackAlreadyFired() =
        runBlocking {
            val channel = Channel<String>()
            val source = channelFlow { for (value in channel) send(value) }
            val results = mutableListOf<String>()
            val job =
                launch {
                    source
                        .withOfflineFallback(isOffline = { true }, timeoutMs = 10L, fallback = { "offline" })
                        .take(2)
                        .toList(results)
                }
            delay(40) // let the watchdog fire "offline" first
            channel.send("late-real-value")
            job.join()
            assertEquals(listOf("offline", "late-real-value"), results)
        }
}
