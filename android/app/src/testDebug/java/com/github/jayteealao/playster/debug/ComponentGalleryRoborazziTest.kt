package com.github.jayteealao.playster.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
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
 * AC: every editorial component renders golden-equal to the recorded
 * baselines in all 4 palettes (4 themed galleries x 4 palettes = 16 JVM
 * goldens at the 412x892 reference viewport, Source face / size M — face
 * and step variation is already golden-covered by the token sample sheet),
 * and no rendered pixel is ever pure #FFFFFF/#000000.
 *
 * Record baselines: ./gradlew recordRoborazziDebug
 * Verify against them: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class ComponentGalleryRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun typographyGallery_allPalettes() = captureGalleryOnAllPalettes(ComponentGalleryPage.TYPOGRAPHY)

    @Test
    fun listsGallery_allPalettes() = captureGalleryOnAllPalettes(ComponentGalleryPage.LISTS)

    @Test
    fun chromeGallery_allPalettes() = captureGalleryOnAllPalettes(ComponentGalleryPage.CHROME)

    @Test
    fun gapStatesGallery_allPalettes() = captureGalleryOnAllPalettes(ComponentGalleryPage.GAP_STATES)

    private fun captureGalleryOnAllPalettes(page: ComponentGalleryPage) {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)

        composeTestRule.setContent {
            EditorialTheme(palette = palette) {
                ComponentGallery(page)
            }
        }

        for (nextPalette in EditorialPalettes.All) {
            palette = nextPalette
            composeTestRule.waitForIdle()

            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/snapshots/images/component_gallery_${page.key}_${nextPalette.key}.png",
            )
            assertNoPurePixels(composeTestRule, "${page.key}/${nextPalette.key}")
        }
    }
}
