package com.github.jayteealao.playster.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.EditorialThemeGate

/**
 * Debug-only host for the component galleries, cold-startable from the
 * shell on the saved palette (seed it with the SET_PALETTE broadcast):
 *
 *   adb shell am start -n com.github.jayteealao.playster/.debug.ComponentGalleryActivity \
 *     --es gallery typography|lists|chrome|gapstates
 *
 * Exercises the production no-flash path (synchronous palette read +
 * window style before setContent), same as the token sample sheet host.
 * Enables emulator screencap evidence for the drop-cap diff and PO
 * eyeballing without any signed-in session.
 */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        EditorialThemeGate.applyPreSetContent(this)
        super.onCreate(savedInstanceState)
        val palette = EditorialThemeGate.savedPalette(this)
        val page = ComponentGalleryPage.fromKey(intent.getStringExtra("gallery"))
        setContent {
            EditorialTheme(palette = palette) {
                ComponentGallery(page)
            }
        }
    }
}
