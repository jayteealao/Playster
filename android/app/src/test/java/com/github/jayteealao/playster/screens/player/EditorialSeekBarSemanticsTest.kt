package com.github.jayteealao.playster.screens.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A11Y-1: the seek bar must expose a `SetProgress` accessibility action wired
 * to [EditorialSeekBar]'s `onScrub` callback, so TalkBack's adjustable-control
 * gesture can move the playhead — not just announce it via
 * `progressBarRangeInfo` (read-only).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class EditorialSeekBarSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun seekBar_exposesSetProgressAction_wiredToOnScrub() {
        var lastScrub: Float? = null
        composeTestRule.setContent {
            EditorialTheme {
                EditorialSeekBar(
                    positionSeconds = 30f,
                    durationSeconds = 120f,
                    onScrub = { lastScrub = it },
                )
            }
        }

        val seekBar = composeTestRule.onNodeWithContentDescription("Seek bar")
        seekBar.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        seekBar.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

        seekBar.performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        assertEquals(0.75f, lastScrub)
    }
}
