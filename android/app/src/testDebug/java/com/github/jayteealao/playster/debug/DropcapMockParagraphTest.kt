package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.DropcapBody
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC: the drop-cap body rendered with the mock's own Summary-tab paragraph
 * (verbatim tldr text) at the 412dp reference viewport with the screen's
 * 22dp gutters, on Cream — the golden that doubles as the visual-diff
 * input against the browser-rendered prototype paragraph.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class DropcapMockParagraphTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dropcapParagraph_matchesRecordedBaseline_onCream() {
        composeTestRule.setContent {
            EditorialTheme {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(LocalEditorialTokens.current.palette.paper)
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                ) {
                    DropcapBody(GallerySample.SUMMARY_TLDR)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/images/dropcap_summary_cream.png",
        )
        assertNoPurePixels(composeTestRule, "dropcap/cream")
    }
}
