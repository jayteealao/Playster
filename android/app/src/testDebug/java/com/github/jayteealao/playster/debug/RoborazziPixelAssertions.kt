package com.github.jayteealao.playster.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Draws the composed content view straight into a bitmap. Direct view draw
 * because Compose's captureToImage() deadlocks under Robolectric — its
 * forceRedraw waits for a draw pass that never comes; drawing the view
 * hierarchy renders the same pixels with no wait. Must run on
 * [AndroidComposeTestRule.runOnIdle]'s thread, so callers pass the rule in.
 */
private fun renderContentToBitmap(
    composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    combo: String,
): Bitmap {
    val content = composeTestRule.activity.findViewById<ViewGroup>(android.R.id.content)
    val view = content.getChildAt(0) ?: content
    assertTrue("$combo: content view has no size", view.width > 0 && view.height > 0)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap
}

/**
 * Shared pixel-purity scan for the editorial JVM screenshot tests: asserts
 * no rendered pixel is ever pure #FFFFFF or #000000 (the palette
 * discipline's absolute ban). Note this does NOT catch a screen that never
 * paints its own background: Robolectric's default window color (~RGB
 * 250,250,250) is not pure white and sails through clean. Pair with
 * [assertRootBackground] on any suite whose subject is expected to paint
 * its own root.
 */
fun assertNoPurePixels(
    composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    combo: String,
) {
    var pureWhite = 0
    var pureBlack = 0
    composeTestRule.runOnIdle {
        val bitmap = renderContentToBitmap(composeTestRule, combo)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        for (argb in pixels) {
            when (argb) {
                PURE_WHITE -> pureWhite++
                PURE_BLACK -> pureBlack++
            }
        }
    }
    assertEquals("$combo: pure #FFFFFF pixels rendered", 0, pureWhite)
    assertEquals("$combo: pure #000000 pixels rendered", 0, pureBlack)
}

/**
 * Confirms a screen actually self-paints: samples the rendered view's
 * top-left corner pixel and asserts byte-exact equality against
 * [expectedPaper] (the `tokens.palette.paper` of the palette under test).
 * This is the check that catches what [assertNoPurePixels] structurally
 * cannot — a screen that relies entirely on a caller-supplied scaffold for
 * its background renders Robolectric's near-white window default instead
 * of the palette's paper, which is not pure white/black and would
 * otherwise pass silently (design-critique DC-1).
 */
fun assertRootBackground(
    composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    combo: String,
    expectedPaper: Color,
) {
    var corner = 0
    composeTestRule.runOnIdle {
        val bitmap = renderContentToBitmap(composeTestRule, combo)
        corner = bitmap.getPixel(0, 0)
        bitmap.recycle()
    }
    val expectedArgb = expectedPaper.toArgb()
    assertEquals(
        "$combo: root background 0x${Integer.toHexString(corner)} != expected paper 0x${Integer.toHexString(expectedArgb)}",
        expectedArgb,
        corner,
    )
}

private const val PURE_WHITE = 0xFFFFFFFF.toInt()
private const val PURE_BLACK = 0xFF000000.toInt()
