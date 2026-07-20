package com.github.jayteealao.playster.screens.player.playback

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A11Y-4: the shared YouTube embed host — the single [AndroidView] the
 * Player's Masthead band and the Transcript's mini-embed strip both attach
 * to via [PlaybackSession] — must carry a `contentDescription` so TalkBack
 * has a landmark label for the video region, rather than falling back to the
 * WebView's own opaque DOM content.
 *
 * No [PlaybackSession.controllerFor] call here, mirroring
 * `TranscriptStateRoborazziTest.embedError` — this asserts the host's own
 * semantics, not a live/initialized embed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class YouTubePlayerHostSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun host_exposesContentDescriptionForTheVideoRegion() {
        val session = PlaybackSession(progressWriteSink = ProgressWriteSink { _, _, _, _, _ -> })
        composeTestRule.setContent {
            EditorialTheme {
                YouTubePlayerHost(session = session)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Video player").assertExists()
    }
}
