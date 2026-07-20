package com.github.jayteealao.playster.screens.player.playback

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.BooleanProvider
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A minimal hand-written stub of the embed's [YouTubePlayer] — the listener
 * under test only ever calls `cueVideo`/`play`/`pause`/`seekTo`/`setPlaybackRate`
 * on it, so those are the only calls recorded.
 */
private class FakeYouTubePlayer : YouTubePlayer {
    var playCalled = false
    var pauseCalled = false
    var lastCuedVideoId: String? = null
    var lastCuedSeconds: Float? = null
    var lastSeekTo: Float? = null
    var lastPlaybackRate: PlayerConstants.PlaybackRate? = null

    override fun loadVideo(
        videoId: String,
        startSeconds: Float,
    ) = Unit

    override fun cueVideo(
        videoId: String,
        startSeconds: Float,
    ) {
        lastCuedVideoId = videoId
        lastCuedSeconds = startSeconds
    }

    override fun play() {
        playCalled = true
    }

    override fun pause() {
        pauseCalled = true
    }

    override fun nextVideo() = Unit

    override fun previousVideo() = Unit

    override fun playVideoAt(index: Int) = Unit

    override fun setLoop(loop: Boolean) = Unit

    override fun setShuffle(shuffle: Boolean) = Unit

    override fun mute() = Unit

    override fun unMute() = Unit

    override fun isMutedAsync(callback: BooleanProvider) = Unit

    override fun setVolume(volumePercent: Int) = Unit

    override fun seekTo(time: Float) {
        lastSeekTo = time
    }

    override fun setPlaybackRate(playbackRate: PlayerConstants.PlaybackRate) {
        lastPlaybackRate = playbackRate
    }

    override fun addListener(listener: YouTubePlayerListener): Boolean = true

    override fun removeListener(listener: YouTubePlayerListener): Boolean = true
}

/**
 * TST-3 — the playback-listener state machine (`onReady`/`onStateChange`/
 * `onError`) and the speed-snap helper, previously covered only for the
 * load-timeout watchdog (`PlaybackControllerTest`). Drives `controller.listener`
 * directly against [FakeYouTubePlayer], the pattern the sibling file already
 * establishes for constructing the controller without a real WebView.
 *
 * Robolectric + a dummy, offline [FirebaseApp] are required here (unlike the
 * sibling suite): `onError` and the PLAYING-recovery branch of `onStateChange`
 * both call into [PlaybackInstrumentation], which logs via `android.util.Log`
 * and touches `Firebase.crashlytics`. Plain JUnit throws immediately on the
 * unmocked `Log.w` call; Robolectric shadows `Log` but still needs a
 * `FirebaseApp` instance for `FirebaseCrashlytics.getInstance()` not to throw
 * `IllegalStateException`. The dummy [FirebaseOptions] below never reach a real
 * network or a real Firebase project — construction is local and deterministic
 * (confirmed empirically: `setCustomKey`/`recordException` are no-ops against
 * this fixture, they do not require a live backend).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControllerListenerTest {
    @Before
    fun setUpFirebase() {
        if (FirebaseApp.getApps(RuntimeEnvironment.getApplication()).isEmpty()) {
            FirebaseApp.initializeApp(
                RuntimeEnvironment.getApplication(),
                FirebaseOptions.Builder()
                    .setApplicationId("1:1:android:1")
                    .setApiKey("test-api-key")
                    .setProjectId("test-project")
                    .build(),
            )
        }
    }

    // ---- onReady: Loading -> Ready, cueVideo, pendingPlayIntent latch, re-cue ----

    @Test
    fun onReady_movesLoadingToReady_andCuesAtStartPosition() {
        val controller = PlaybackController(videoId = "vid", startPositionSeconds = 12f, isOffline = { false })
        val player = FakeYouTubePlayer()

        controller.listener.onReady(player)

        assertEquals(PlaybackState.Ready, controller.state.value)
        assertEquals("vid", player.lastCuedVideoId)
        assertEquals(12f, player.lastCuedSeconds)
    }

    @Test
    fun onReady_secondTime_afterPlaying_doesNotRevertToReady() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onReady(player)
        controller.listener.onStateChange(player, PlayerConstants.PlayerState.PLAYING)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // A config change re-initializes the same controller and fires onReady again.
        controller.listener.onReady(player)

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun onReady_reCuesFromLastObservedPosition_whenAheadOfStart() {
        val controller = PlaybackController(videoId = "vid", startPositionSeconds = 10f, isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onCurrentSecond(player, 45f)

        controller.listener.onReady(player)

        // maxOf(start=10, lastObserved=45) - rotation never silently rewinds progress.
        assertEquals(45f, player.lastCuedSeconds)
    }

    @Test
    fun onReady_reCuesFromStart_whenLastObservedIsBehindStart() {
        val controller = PlaybackController(videoId = "vid", startPositionSeconds = 50f, isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onCurrentSecond(player, 5f)

        controller.listener.onReady(player)

        // maxOf(start=50, lastObserved=5) - the constructor-fixed resume point wins.
        assertEquals(50f, player.lastCuedSeconds)
    }

    @Test
    fun onReady_appliesPendingPlayIntent_playRequestedBeforeReady() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.play() // player is still null - queued, not dropped (BC-2).
        val player = FakeYouTubePlayer()

        controller.listener.onReady(player)

        assertTrue(player.playCalled)
        assertFalse(player.pauseCalled)
    }

    @Test
    fun onReady_appliesPendingPlayIntent_pauseRequestedBeforeReady() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.pause() // player is still null - queued as a "don't autoplay" intent.
        val player = FakeYouTubePlayer()

        controller.listener.onReady(player)

        assertTrue(player.pauseCalled)
        assertFalse(player.playCalled)
    }

    @Test
    fun onReady_withoutAnyPriorIntent_neitherPlaysNorPauses() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        val player = FakeYouTubePlayer()

        controller.listener.onReady(player)

        assertFalse(player.playCalled)
        assertFalse(player.pauseCalled)
    }

    // ---- onStateChange: the 7-branch state machine ----

    @Test
    fun onStateChange_playing_fromFreshController_setsPlaying() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.PLAYING)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun onStateChange_paused_fromFreshController_setsPaused() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.PAUSED)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun onStateChange_buffering_fromFreshController_setsBuffering() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.BUFFERING)
        assertEquals(PlaybackState.Buffering, controller.state.value)
    }

    @Test
    fun onStateChange_ended_fromFreshController_setsEnded() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.ENDED)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun onStateChange_videoCued_whileLoading_setsReady() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.VIDEO_CUED)
        assertEquals(PlaybackState.Ready, controller.state.value)
    }

    @Test
    fun onStateChange_videoCued_whileNotLoading_isNoOp() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.PLAYING)

        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.VIDEO_CUED)

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun onStateChange_unstartedAndUnknown_areNoOps() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.UNSTARTED)
        assertEquals(PlaybackState.Loading, controller.state.value)
        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.UNKNOWN)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun onStateChange_paused_neverClobbersAnExistingError() {
        val controller = PlaybackController(videoId = "vid", isOffline = { true })
        controller.onLoadTimedOut()
        assertTrue(controller.state.value is PlaybackState.Error)

        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.PAUSED)

        assertTrue(controller.state.value is PlaybackState.Error)
    }

    @Test
    fun onStateChange_buffering_neverClobbersAnExistingError() {
        val controller = PlaybackController(videoId = "vid", isOffline = { true })
        controller.onLoadTimedOut()

        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.BUFFERING)

        assertTrue(controller.state.value is PlaybackState.Error)
    }

    @Test
    fun onStateChange_ended_neverClobbersAnExistingError() {
        val controller = PlaybackController(videoId = "vid", isOffline = { true })
        controller.onLoadTimedOut()

        controller.listener.onStateChange(FakeYouTubePlayer(), PlayerConstants.PlayerState.ENDED)

        assertTrue(controller.state.value is PlaybackState.Error)
    }

    @Test
    fun onStateChange_playing_clearsAnExistingErrorAndRecovers() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onError(player, PlayerConstants.PlayerError.VIDEO_NOT_FOUND)
        assertTrue(controller.state.value is PlaybackState.Error)

        controller.listener.onStateChange(player, PlayerConstants.PlayerState.PLAYING)

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    // ---- onError: the entry point into PlaybackError.from mapping ----

    @Test
    fun onError_mapsTheLibraryErrorThroughPlaybackErrorFrom() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onError(FakeYouTubePlayer(), PlayerConstants.PlayerError.VIDEO_NOT_FOUND)
        assertEquals(PlaybackState.Error(PlaybackError.Unavailable), controller.state.value)
    }

    @Test
    fun onError_whileOffline_mapsToOfflineRegardlessOfTheLibraryCode() {
        val controller = PlaybackController(videoId = "vid", isOffline = { true })
        controller.listener.onError(FakeYouTubePlayer(), PlayerConstants.PlayerError.HTML_5_PLAYER)
        assertEquals(PlaybackState.Error(PlaybackError.Offline), controller.state.value)
    }

    // ---- retry(): error -> Loading, re-cue at last position, re-arms the watchdog ----

    @Test
    fun retry_whenNotInError_isNoOp() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.retry()
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun retry_fromError_resetsToLoadingAndReCuesAtTheLastKnownPosition() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onReady(player)
        controller.listener.onCurrentSecond(player, 30f)
        controller.listener.onError(player, PlayerConstants.PlayerError.VIDEO_NOT_FOUND)
        assertTrue(controller.state.value is PlaybackState.Error)

        controller.retry()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals("vid", player.lastCuedVideoId)
        assertEquals(30f, player.lastCuedSeconds)
    }

    @Test
    fun retry_reArmsTheLoadWatchdog() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onReady(player)
        controller.listener.onError(player, PlayerConstants.PlayerError.VIDEO_NOT_FOUND)

        controller.retry()
        // Loading again after retry() - a fresh watchdog tick must be able to fire.
        val surfaced = controller.onLoadTimedOut()

        assertEquals(PlaybackError.Unknown, surfaced)
        assertTrue(controller.state.value is PlaybackState.Error)
    }

    // ---- speed-snap: clamping/snapping to the nearest supported playback rate ----

    @Test
    fun setSpeed_snapsToNearestSupportedRate() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        val player = FakeYouTubePlayer()
        controller.listener.onReady(player)

        controller.setSpeed(0.6f)

        assertEquals(PlayerConstants.PlaybackRate.RATE_0_75, player.lastPlaybackRate)
    }

    @Test
    fun toPlaybackRate_snapsAndClampsAcrossTheFullSupportedRange() {
        val table =
            listOf(
                // Below the lowest supported rate - clamps.
                0f to PlayerConstants.PlaybackRate.RATE_0_25,
                0.25f to PlayerConstants.PlaybackRate.RATE_0_25,
                0.26f to PlayerConstants.PlaybackRate.RATE_0_5,
                0.5f to PlayerConstants.PlaybackRate.RATE_0_5,
                0.6f to PlayerConstants.PlaybackRate.RATE_0_75,
                0.75f to PlayerConstants.PlaybackRate.RATE_0_75,
                1.0f to PlayerConstants.PlaybackRate.RATE_1,
                1.1f to PlayerConstants.PlaybackRate.RATE_1_25,
                1.25f to PlayerConstants.PlaybackRate.RATE_1_25,
                1.4f to PlayerConstants.PlaybackRate.RATE_1_5,
                1.5f to PlayerConstants.PlaybackRate.RATE_1_5,
                1.6f to PlayerConstants.PlaybackRate.RATE_1_75,
                1.75f to PlayerConstants.PlaybackRate.RATE_1_75,
                1.76f to PlayerConstants.PlaybackRate.RATE_2,
                // Above the highest supported rate - clamps.
                3.0f to PlayerConstants.PlaybackRate.RATE_2,
            )
        table.forEach { (input, expected) ->
            assertEquals("toPlaybackRate($input)", expected, input.toPlaybackRate())
        }
    }

    // ---- position/duration streams (no listener branching, but part of the state machine) ----

    @Test
    fun onCurrentSecond_publishesToPositionStream() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        controller.listener.onCurrentSecond(FakeYouTubePlayer(), 21f)
        assertEquals(21f, controller.positionSeconds.value)
    }

    @Test
    fun onVideoDuration_publishesToDurationStream() {
        val controller = PlaybackController(videoId = "vid", isOffline = { false })
        assertEquals(0f, controller.durationSeconds.value)
        controller.listener.onVideoDuration(FakeYouTubePlayer(), 300f)
        assertEquals(300f, controller.durationSeconds.value)
    }
}
