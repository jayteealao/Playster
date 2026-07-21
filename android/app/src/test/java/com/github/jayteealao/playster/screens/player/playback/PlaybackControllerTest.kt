package com.github.jayteealao.playster.screens.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC4 — the load watchdog. A network loss during initial load makes the IFrame
 * fire neither onReady nor onError, so [PlaybackController] would sit in
 * [PlaybackState.Loading] forever; [PlaybackController.onLoadTimedOut] converts
 * that silent stall into the editorial error surface. Pure JVM (no embed, no
 * Android): the method only reads the offline probe and mutates state.
 */
class PlaybackControllerTest {
    @Test
    fun startsInLoading() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun loadTimeout_whileOffline_surfacesOffline() {
        val controller = PlaybackController(videoId = "vid", isOffline = { true })

        val surfaced = controller.onLoadTimedOut()

        assertEquals(PlaybackError.Offline, surfaced)
        assertEquals(PlaybackState.Error(PlaybackError.Offline), controller.state.value)
    }

    @Test
    fun loadTimeout_whileOnline_surfacesRetryableUnknown() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })

        val surfaced = controller.onLoadTimedOut()

        assertEquals(PlaybackError.Unknown, surfaced)
        assertEquals(PlaybackState.Error(PlaybackError.Unknown), controller.state.value)
        // A retryable fault must offer a retry affordance.
        assertTrue(!PlaybackError.Unknown.retryLabel.isNullOrBlank())
    }

    @Test
    fun loadTimeout_isIdempotent_andNeverClobbersAnExistingError() {
        val controller = PlaybackController(videoId = "vid", isOffline = { true })

        val first = controller.onLoadTimedOut()
        val second = controller.onLoadTimedOut()

        assertEquals(PlaybackError.Offline, first)
        // Once resolved out of Loading, a late watchdog tick is a no-op.
        assertNull(second)
        assertEquals(PlaybackState.Error(PlaybackError.Offline), controller.state.value)
    }
}
