package com.github.jayteealao.playster.ui.editorial.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.PaperPalette
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * AC: the gap states hold the editorial voice on every palette — no
 * Material spinner (no indeterminate-progress node anywhere in the tree),
 * no snackbar vocabulary in the component package, the empty state names
 * what will appear and offers an actionable path, and the error state
 * carries its specific message plus an action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class EditorialGapStateVoiceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun gapStates_holdTheEditorialVoice_onAllFourPalettes() {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)

        composeTestRule.setContent {
            EditorialTheme(palette = palette) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    EditorialLoadingNotice(label = LOADING_LABEL)
                    EditorialEmptyNotice(
                        title = EMPTY_TITLE,
                        teaches = EMPTY_TEACHES,
                        actionLabel = EMPTY_ACTION,
                        onAction = {},
                    )
                    EditorialErrorNotice(
                        message = ERROR_MESSAGE,
                        actionLabel = ERROR_ACTION,
                        onAction = {},
                    )
                    EditorialQuotaNotice(message = QUOTA_MESSAGE)
                }
            }
        }

        for (nextPalette in EditorialPalettes.All) {
            palette = nextPalette
            composeTestRule.waitForIdle()
            val combo = nextPalette.key

            // No spinner anywhere: zero indeterminate progress nodes.
            composeTestRule.onAllNodes(indeterminateProgress()).assertCountEquals(0)

            // Loading is a static sentence, not motion.
            composeTestRule.onNodeWithText(LOADING_LABEL).assertExists("$combo: loading label missing")

            // Empty teaches (names what will appear) and offers a path.
            composeTestRule.onNodeWithText(EMPTY_TITLE).assertExists("$combo: empty title missing")
            composeTestRule.onNodeWithText(EMPTY_TEACHES).assertExists("$combo: empty state teaches nothing")
            composeTestRule.onNodeWithText(EMPTY_ACTION).assertHasClickAction()

            // Error is specific and actionable.
            composeTestRule.onNodeWithText(ERROR_MESSAGE).assertExists("$combo: error message missing")
            composeTestRule.onNodeWithText(ERROR_ACTION).assertHasClickAction()

            // Quota notice carries its message.
            composeTestRule.onNodeWithText(QUOTA_MESSAGE).assertExists("$combo: quota message missing")
        }
    }

    /**
     * The Material ban, held at the source level: nothing in the editorial
     * component package may reference the Material spinner/snackbar
     * vocabulary. (Tree assertions can prove a spinner is absent from these
     * sample trees; the source scan proves no component can ever compose one.)
     */
    @Test
    fun componentPackage_neverReferencesMaterialSpinnerOrSnackbar() {
        val packageDir = File("src/main/java/com/github/jayteealao/playster/ui/editorial/components")
        assertTrue("component package missing at ${packageDir.absolutePath}", packageDir.isDirectory)
        val banned = listOf("CircularProgressIndicator", "LinearProgressIndicator", "Snackbar")
        packageDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            banned.forEach { identifier ->
                assertFalse(
                    "${file.name} references banned Material vocabulary: $identifier",
                    source.contains(identifier),
                )
            }
        }
    }

    private fun indeterminateProgress(): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate,
        )

    private companion object {
        const val LOADING_LABEL = "Fetching the shelf — a moment."
        const val EMPTY_TITLE = "Nothing on the shelf yet."
        const val EMPTY_TEACHES = "Playlists you save will appear here, ordered by last opened."
        const val EMPTY_ACTION = "Find a playlist"
        const val ERROR_MESSAGE = "The transcript didn't arrive — Playster couldn't reach the library."
        const val ERROR_ACTION = "Try again"
        const val QUOTA_MESSAGE = "You've reached today's summary quota. It resets at midnight."
    }
}
