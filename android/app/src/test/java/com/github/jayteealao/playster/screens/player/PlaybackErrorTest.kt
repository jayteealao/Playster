package com.github.jayteealao.playster.screens.player

import com.github.jayteealao.playster.screens.player.playback.PlaybackError
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC3 — the pure error-code → editorial-state mapping. The library collapses raw
 * IFrame codes into [PlayerConstants.PlayerError] before we see them (verified
 * against the 13.0.0 source), so the mapper is over the enum: embed-disabled
 * (101/150), unavailable (100), referer (153), and any other fault, with an
 * offline flag that wins over a concrete error (the airplane-mode launch path).
 */
class PlaybackErrorTest {
    @Test
    fun embedDisabled_maps() {
        assertEquals(
            PlaybackError.EmbedDisabled,
            PlaybackError.from(PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER),
        )
    }

    @Test
    fun videoNotFound_mapsToUnavailable() {
        assertEquals(PlaybackError.Unavailable, PlaybackError.from(PlayerConstants.PlayerError.VIDEO_NOT_FOUND))
    }

    @Test
    fun refererMissing_maps() {
        assertEquals(
            PlaybackError.Referer,
            PlaybackError.from(PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER),
        )
    }

    @Test
    fun otherFaults_mapToUnknown() {
        assertEquals(
            PlaybackError.Unknown,
            PlaybackError.from(PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST),
        )
        assertEquals(PlaybackError.Unknown, PlaybackError.from(PlayerConstants.PlayerError.HTML_5_PLAYER))
        assertEquals(PlaybackError.Unknown, PlaybackError.from(PlayerConstants.PlayerError.UNKNOWN))
    }

    @Test
    fun offline_winsOverConcreteError() {
        assertEquals(
            PlaybackError.Offline,
            PlaybackError.from(PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER, offline = true),
        )
        assertEquals(PlaybackError.Offline, PlaybackError.from(PlayerConstants.PlayerError.UNKNOWN, offline = true))
    }

    @Test
    fun everyStateHasAnEditorialMessage_andNoStatePretendsPlayback() {
        val states =
            listOf(
                PlaybackError.EmbedDisabled,
                PlaybackError.Unavailable,
                PlaybackError.Referer,
                PlaybackError.Offline,
                PlaybackError.Unknown,
            )
        // Every state carries a specific, non-blank editorial message.
        assertTrue(states.all { it.editorialMessage.isNotBlank() })
        // Permanent faults offer no false retry; transient faults do.
        assertNull(PlaybackError.EmbedDisabled.retryLabel)
        assertNull(PlaybackError.Unavailable.retryLabel)
        assertTrue(!PlaybackError.Referer.retryLabel.isNullOrBlank())
    }
}
