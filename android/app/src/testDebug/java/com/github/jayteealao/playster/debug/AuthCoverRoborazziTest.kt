package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.screens.auth.AuthCoverPage
import com.github.jayteealao.playster.screens.auth.AuthUiState
import com.github.jayteealao.playster.screens.auth.FailureKind
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.PaperPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC2: the auth cover renders editorially in idle / authenticating / failed
 * across all four palettes (3 states x 4 palettes = 12 JVM goldens at the
 * 412x892 reference viewport, Source face / size M), and no rendered pixel
 * is ever pure #FFFFFF/#000000. The stateless [AuthCoverPage] is the subject,
 * so every state renders with no Credential Manager or Firebase in the graph.
 *
 * Record baselines: ./gradlew recordRoborazziDebug
 * Verify against them: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class AuthCoverRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun idle_allPalettes() = captureStateOnAllPalettes("idle", AuthUiState.Idle)

    @Test
    fun authenticating_allPalettes() = captureStateOnAllPalettes("authenticating", AuthUiState.Authenticating)

    @Test
    fun failed_allPalettes() = captureStateOnAllPalettes("failed", AuthUiState.Failed(FailureKind.Network))

    private fun captureStateOnAllPalettes(
        stateKey: String,
        state: AuthUiState,
    ) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)

        composeTestRule.setContent {
            EditorialTheme(palette = palette) {
                AuthCoverPage(state = state, onSignIn = {})
            }
        }

        for (nextPalette in EditorialPalettes.All) {
            palette = nextPalette
            composeTestRule.waitForIdle()

            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/snapshots/images/auth_cover_${stateKey}_${nextPalette.key}.png",
            )
            assertNoPurePixels(composeTestRule, "auth_cover/$stateKey/${nextPalette.key}")
        }
    }
}
