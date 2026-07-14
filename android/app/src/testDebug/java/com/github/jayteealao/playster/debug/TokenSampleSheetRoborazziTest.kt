package com.github.jayteealao.playster.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.jayteealao.playster.ui.editorial.EditorialFace
import com.github.jayteealao.playster.ui.editorial.EditorialFaces
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.PaperPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AC: the token sample sheet matches the recorded baselines in all
 * 4 palettes x 4 display faces (JVM screenshot regression at the 412x892
 * reference viewport), and no rendered pixel is ever pure #FFFFFF/#000000.
 *
 * Record baselines: ./gradlew recordRoborazziDebug
 * Verify against them: ./gradlew verifyRoborazziDebug
 * The pixel-purity scan runs on every plain testDebugUnitTest execution.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-420dpi")
class TokenSampleSheetRoborazziTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tokenSampleSheet_all16PaletteFaceCombinations() {
        var palette by mutableStateOf<PaperPalette>(EditorialPalettes.Cream)
        var face by mutableStateOf<EditorialFace>(EditorialFaces.Source)

        composeTestRule.setContent {
            EditorialTheme(palette = palette, face = face) {
                TokenSampleSheet()
            }
        }

        for (nextPalette in EditorialPalettes.All) {
            for (nextFace in EditorialFaces.All) {
                palette = nextPalette
                face = nextFace
                composeTestRule.waitForIdle()

                composeTestRule.onRoot().captureRoboImage(
                    filePath = "src/test/snapshots/images/token_sheet_${nextPalette.key}_${nextFace.key}.png",
                )
                assertNoPurePixels("${nextPalette.key}/${nextFace.key}")
            }
        }
    }

    /**
     * Draws the composed content view into a bitmap and scans every pixel.
     * (captureToImage's forceRedraw never completes under Robolectric, so
     * this renders the view hierarchy directly — same pixels, no redraw wait.)
     */
    private fun assertNoPurePixels(combo: String) {
        var pureWhite = 0
        var pureBlack = 0
        composeTestRule.runOnIdle {
            val content = composeTestRule.activity.findViewById<ViewGroup>(android.R.id.content)
            val view = content.getChildAt(0) ?: content
            assertTrue("$combo: content view has no size", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            bitmap.recycle()
            for (argb in pixels) {
                when (argb) {
                    0xFFFFFFFF.toInt() -> pureWhite++
                    0xFF000000.toInt() -> pureBlack++
                }
            }
        }
        assertEquals("$combo: pure #FFFFFF pixels rendered", 0, pureWhite)
        assertEquals("$combo: pure #000000 pixels rendered", 0, pureBlack)
    }
}
