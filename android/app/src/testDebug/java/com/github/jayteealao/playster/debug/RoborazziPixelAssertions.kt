package com.github.jayteealao.playster.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Shared pixel-purity scan for the editorial JVM screenshot tests: draws
 * the composed content view straight into a bitmap and asserts no rendered
 * pixel is ever pure #FFFFFF or #000000 (the palette discipline's absolute
 * ban). Direct view draw because Compose's captureToImage() deadlocks
 * under Robolectric — its forceRedraw waits for a draw pass that never
 * comes; drawing the view hierarchy renders the same pixels with no wait.
 */
fun assertNoPurePixels(
    composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    combo: String,
) {
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
                PURE_WHITE -> pureWhite++
                PURE_BLACK -> pureBlack++
            }
        }
    }
    assertEquals("$combo: pure #FFFFFF pixels rendered", 0, pureWhite)
    assertEquals("$combo: pure #000000 pixels rendered", 0, pureBlack)
}

private const val PURE_WHITE = 0xFFFFFFFF.toInt()
private const val PURE_BLACK = 0xFF000000.toInt()
