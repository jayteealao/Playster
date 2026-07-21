package com.github.jayteealao.playster.ui.editorial.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC: every interactive editorial component exposes TalkBack semantics
 * (role + label) and a touch target of at least 48x48dp — even where the
 * visible control is a hairline-separated row or a small pill. The visual
 * boxes stay mock-dense; the platform's touch-bounds expansion on
 * clickable nodes supplies the invisible extension, and this test is the
 * evidence that it actually happens for every component.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class EditorialComponentSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun buttonsAndChip_haveRoleLabelAndTouchTarget() {
        composeTestRule.setContent { AllInteractiveComponents() }
        listOf(
            "pill-button" to "Continue · Ep 9",
            "text-action" to "Download",
            "chip" to "design tokens",
        ).forEach { (tag, label) ->
            composeTestRule.onNodeWithTag(tag)
                .assert(hasRole(Role.Button))
                .assert(hasText(label))
                .assertHasClickAction()
                .assertTouchTargetAtLeast()
        }
    }

    @Test
    fun rows_mergeIntoLabelledButtonTargets() {
        composeTestRule.setContent { AllInteractiveComponents() }
        listOf(
            "episode-row" to "The Future of Design Systems",
            "shelf-row" to "Designing Better Interfaces",
            "mini-player" to "The Future of Design Systems",
        ).forEach { (tag, label) ->
            composeTestRule.onNodeWithTag(tag)
                .assert(hasRole(Role.Button))
                .assert(hasText(label, substring = true))
                .assertHasClickAction()
                .assertTouchTargetAtLeast()
        }
    }

    @Test
    fun tabsAndNavItems_exposeSelectionSemanticsAndTouchTargets() {
        composeTestRule.setContent { AllInteractiveComponents() }
        // 3 tabs + 4 bottom-nav items.
        val selectables = composeTestRule.onAllNodes(isSelectable())
        selectables.assertCountEquals(7)
        repeat(7) { index ->
            selectables[index]
                .assert(hasRole(Role.Tab))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assertHasClickAction()
                .assertTouchTargetAtLeast()
        }
    }

    @Test
    fun iconOnlyActions_carryContentDescriptions() {
        composeTestRule.setContent { AllInteractiveComponents() }
        composeTestRule.onNodeWithContentDescription("Back")
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            .assertTouchTargetAtLeast()
        composeTestRule.onNodeWithContentDescription("Pause")
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            .assertTouchTargetAtLeast()
    }

    @Test
    fun gapStateActions_areRealButtons() {
        composeTestRule.setContent { AllInteractiveComponents() }
        listOf("Find a playlist", "Try again").forEach { label ->
            composeTestRule.onNode(hasText(label).and(hasRole(Role.Button)))
                .assertHasClickAction()
                .assertTouchTargetAtLeast()
        }
    }

    private fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /**
     * Asserts the node's touch bounds measure at least [MIN_TOUCH_DP] on
     * both axes ([androidx.compose.ui.semantics.SemanticsNode.touchBoundsInRoot]
     * — the clickable-node bounds after minimum-touch-target expansion).
     */
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val widthDp = node.touchBoundsInRoot.width / density
        val heightDp = node.touchBoundsInRoot.height / density
        assertTrue(
            "touch target ${widthDp}x${heightDp}dp is below the ${MIN_TOUCH_DP}dp floor",
            widthDp + TOLERANCE_DP >= MIN_TOUCH_DP && heightDp + TOLERANCE_DP >= MIN_TOUCH_DP,
        )
        return this
    }

    private companion object {
        const val MIN_TOUCH_DP = 48f
        const val TOLERANCE_DP = 0.5f
    }
}

/** Every interactive component in the library, tagged for the assertions above. */
@Composable
private fun AllInteractiveComponents() {
    EditorialTheme {
        Column(modifier = Modifier.fillMaxWidth()) {
            EditorialPillButton(
                text = "Continue · Ep 9",
                onClick = {},
                icon = EditorialIcons.Play,
                modifier = Modifier.testTag("pill-button"),
            )
            EditorialTextAction(
                text = "Download",
                onClick = {},
                icon = EditorialIcons.Download,
                modifier = Modifier.testTag("text-action"),
            )
            EditorialChip(
                text = "design tokens",
                onClick = {},
                modifier = Modifier.testTag("chip"),
            )
            EditorialTabs(
                tabs = listOf("Episodes", "Summary", "Notes"),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.testTag("tabs"),
            )
            EditorialBottomNav(
                items = listOf("Reading", "Library", "Search", "You"),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.testTag("bottom-nav"),
            )
            EditorialAppBar(
                kicker = "Now playing",
                left = {
                    EditorialAppBarAction(
                        icon = EditorialIcons.Back,
                        contentDescription = "Back",
                        onClick = {},
                    )
                },
            )
            EpisodeRow(
                position = 9,
                title = "The Future of Design Systems",
                duration = "23:14",
                onClick = {},
                playing = true,
                modifier = Modifier.testTag("episode-row"),
            )
            ShelfRow(
                ordinal = 1,
                kicker = "Design · Vol. 01",
                title = "Designing Better Interfaces",
                byline = "by Andy Allen · 24 videos · 8h 14m",
                onClick = {},
                progress = 0.55f,
                modifier = Modifier.testTag("shelf-row"),
            )
            MiniPlayerPill(
                title = "The Future of Design Systems",
                position = "12:47",
                playing = true,
                onClick = {},
                onPlayPause = {},
                modifier = Modifier.testTag("mini-player"),
            )
            EditorialEmptyNotice(
                title = "Nothing on the shelf yet.",
                teaches = "Playlists you save will appear here.",
                actionLabel = "Find a playlist",
                onAction = {},
            )
            EditorialErrorNotice(
                message = "The transcript didn't arrive.",
                actionLabel = "Try again",
                onAction = {},
            )
        }
    }
}
