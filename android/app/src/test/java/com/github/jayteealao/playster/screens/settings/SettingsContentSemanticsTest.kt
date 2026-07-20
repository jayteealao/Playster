package com.github.jayteealao.playster.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A11Y-6: the reading-preference option pills (Paper/Face/Size/Line height) —
 * including the palette picker — must announce their selected state to
 * TalkBack rather than conveying it by fill color alone. Selection now goes
 * through `Modifier.selectable(selected = ..., role = Role.RadioButton)`,
 * mirroring `EditorialTabs`/`EditorialBottomNav`'s proven pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class SettingsContentSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionPills_announceSelectedStateAndRadioButtonRole() {
        composeTestRule.setContent {
            EditorialTheme { Content() }
        }

        // Expand the "Paper" axis to render its option pills.
        composeTestRule.onNodeWithTag("settings-row-paper").performClick()

        val selectedPill = composeTestRule.onNodeWithTag("settings-option-paper-cream")
        selectedPill.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        selectedPill.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        val unselectedPill = composeTestRule.onNodeWithTag("settings-option-paper-night")
        unselectedPill.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        unselectedPill.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
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
            stats = emptyList(),
            readingAxes =
                listOf(
                    SettingsUiState.AxisRow(
                        axis = SettingsAxis.PAPER,
                        title = "Paper",
                        currentLabel = "Cream",
                        options =
                            listOf(
                                SettingsUiState.AxisOption("cream", "Cream", true),
                                SettingsUiState.AxisOption("vellum", "Vellum", false),
                                SettingsUiState.AxisOption("newsprint", "Newsprint", false),
                                SettingsUiState.AxisOption("night", "Night", false),
                            ),
                    ),
                ),
            defaultSpeed =
                SettingsUiState.SpeedRow(
                    currentLabel = "1×",
                    options = listOf(SettingsUiState.SpeedOption(1.0f, "1×", true)),
                ),
            version = "Playster · v0.2.1 · a YouTube reader",
            licenses = emptyList(),
        )
}
