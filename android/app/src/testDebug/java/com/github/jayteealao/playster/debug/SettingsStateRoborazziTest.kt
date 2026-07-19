package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.screens.settings.SettingsAxis
import com.github.jayteealao.playster.screens.settings.SettingsContent
import com.github.jayteealao.playster.screens.settings.SettingsUiState
import com.github.jayteealao.playster.ui.editorial.EditorialFace
import com.github.jayteealao.playster.ui.editorial.EditorialFaces
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.PaperPalette
import com.github.jayteealao.playster.ui.editorial.SizeStep
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC6 mechanical leg (the device-free pixel proxy) + AC2's per-face/size matrix:
 * the full Settings screen renders editorially — the profile masthead, the 3-up
 * stats rule, the live reading axes, the default-speed row, the account sign-out,
 * and the version line — across all four palettes and the face/size matrix, no
 * pixel ever pure #FFFFFF/#000000. The dropped ToS/library rows are absent by
 * construction. Stateless [SettingsContent] is the subject, so every state renders
 * without a ViewModel, Hilt, or the network.
 *
 * Record: ./gradlew recordRoborazziDebug   Verify: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class SettingsStateRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings_allPalettes() {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)
        composeTestRule.setContent {
            EditorialTheme(palette = palette) { Content() }
        }
        for (next in EditorialPalettes.All) {
            palette = next
            composeTestRule.waitForIdle()
            composeTestRule.onRoot()
                .captureRoboImage(filePath = "src/test/snapshots/images/settings_state_${next.key}.png")
            assertNoPurePixels(composeTestRule, "settings_state/${next.key}")
        }
    }

    @Test
    fun settings_faceMatrix() {
        var face by mutableStateOf<EditorialFace>(EditorialFaces.Source)
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream, face = face) { Content() }
        }
        for (next in EditorialFaces.All) {
            face = next
            composeTestRule.waitForIdle()
            composeTestRule.onRoot()
                .captureRoboImage(filePath = "src/test/snapshots/images/settings_state_face_${next.key}.png")
            assertNoPurePixels(composeTestRule, "settings_state/face/${next.key}")
        }
    }

    @Test
    fun settings_sizeMatrix() {
        var size by mutableStateOf(SizeStep.M)
        composeTestRule.setContent {
            EditorialTheme(palette = EditorialPalettes.Cream, sizeStep = size) { Content() }
        }
        for (next in SizeStep.entries) {
            size = next
            composeTestRule.waitForIdle()
            composeTestRule.onRoot()
                .captureRoboImage(filePath = "src/test/snapshots/images/settings_state_size_${next.key}.png")
            assertNoPurePixels(composeTestRule, "settings_state/size/${next.key}")
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        SettingsContent(
            state = fixtureState(),
            onSelectAxis = { _, _ -> },
            onSetDefaultSpeed = {},
            onSignOut = {},
        )
    }

    private fun fixtureState(): SettingsUiState =
        SettingsUiState(
            profile =
                SettingsUiState.Profile(
                    sinceLabel = "Subscriber since March 2024",
                    name = "Jamie Reyes",
                    deck = "23 playlists on your shelf. 184 transcripts saved. 12 highlights this week.",
                ),
            stats =
                listOf(
                    SettingsUiState.Stat("12", "day streak"),
                    SettingsUiState.Stat("4.2h", "this week"),
                    SettingsUiState.Stat("1.25×", "avg speed"),
                ),
            readingAxes =
                listOf(
                    axis(SettingsAxis.FACE, "Type face", "Source Serif", "source", faceOptions()),
                    axis(SettingsAxis.SIZE, "Type size", "M", "m", listOf("s" to "S", "m" to "M", "l" to "L", "xl" to "XL")),
                    axis(
                        SettingsAxis.LINE_HEIGHT,
                        "Line height",
                        "Comfortable",
                        "comfortable",
                        listOf("tight" to "Tight", "comfortable" to "Comfortable", "airy" to "Airy"),
                    ),
                    axis(
                        SettingsAxis.PAPER,
                        "Paper",
                        "Cream",
                        "cream",
                        listOf("cream" to "Cream", "vellum" to "Vellum", "newsprint" to "Newsprint", "night" to "Night"),
                    ),
                ),
            defaultSpeed =
                SettingsUiState.SpeedRow(
                    currentLabel = "1.25×",
                    options =
                        listOf(
                            SettingsUiState.SpeedOption(1.0f, "1×", false),
                            SettingsUiState.SpeedOption(1.25f, "1.25×", true),
                            SettingsUiState.SpeedOption(1.5f, "1.5×", false),
                        ),
                ),
            version = "Playster · v0.2.1 · a YouTube reader",
            licenses = emptyList(),
        )

    private fun faceOptions() =
        listOf("source" to "Source Serif", "garamond" to "EB Garamond", "cormorant" to "Cormorant", "fraunces" to "Fraunces")

    private fun axis(
        axis: SettingsAxis,
        title: String,
        current: String,
        selectedKey: String,
        options: List<Pair<String, String>>,
    ) = SettingsUiState.AxisRow(
        axis = axis,
        title = title,
        currentLabel = current,
        options = options.map { (key, label) -> SettingsUiState.AxisOption(key, label, key == selectedKey) },
    )
}
