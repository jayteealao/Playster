package com.github.jayteealao.playster.ui.editorial

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.Density
import androidx.core.content.res.ResourcesCompat
import com.github.jayteealao.playster.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AC: the five bundled families resolve strictly from res/font — every font
 * is a ResourceFont with an explicit wght axis (no downloadable-fonts path
 * anywhere) — and Cormorant/Fraunces body text falls back to Source Serif 4
 * exactly as the mock's FACES table encodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorialFontResolutionTest {
    private val expectedResIdsByFamily =
        mapOf(
            "InterTight" to Triple(EditorialFonts.InterTight, R.font.inter_tight_var, R.font.inter_tight_italic_var),
            "SourceSerif4" to
                Triple(EditorialFonts.SourceSerif4, R.font.source_serif4_var, R.font.source_serif4_italic_var),
            "EBGaramond" to Triple(EditorialFonts.EBGaramond, R.font.eb_garamond_var, R.font.eb_garamond_italic_var),
            "Fraunces" to Triple(EditorialFonts.Fraunces, R.font.fraunces_var, R.font.fraunces_italic_var),
            "CormorantGaramond" to
                Triple(
                    EditorialFonts.CormorantGaramond,
                    R.font.cormorant_garamond_var,
                    R.font.cormorant_garamond_italic_var,
                ),
        )

    @Test
    fun `every font in every family is a bundled ResourceFont with a wght axis`() {
        val density = Density(1f)
        for ((name, spec) in expectedResIdsByFamily) {
            val (family, uprightRes, italicRes) = spec
            assertTrue("$name must be a font-list family", family is FontListFontFamily)
            val fonts = (family as FontListFontFamily).fonts
            // 4 ramp weights x upright + italic.
            assertEquals("$name font count", 8, fonts.size)
            val coveredWeights = mutableSetOf<Pair<Int, FontStyle>>()
            for (font in fonts) {
                assertTrue(
                    "$name: every font must resolve from res/font (found ${font::class.simpleName})",
                    font is ResourceFont,
                )
                font as ResourceFont
                val expectedRes = if (font.style == FontStyle.Italic) italicRes else uprightRes
                assertEquals("$name ${font.weight}/${font.style} resId", expectedRes, font.resId)
                val settings = font.variationSettings.settings
                assertEquals("$name ${font.weight}/${font.style} must set exactly one axis", 1, settings.size)
                assertEquals("wght", settings.single().axisName)
                assertEquals(
                    "$name wght value must match the declared weight",
                    font.weight.weight.toFloat(),
                    settings.single().toVariationValue(density),
                    0f,
                )
                coveredWeights += font.weight.weight to font.style
            }
            for (weight in listOf(400, 500, 600, 700)) {
                assertTrue("$name missing upright w$weight", weight to FontStyle.Normal in coveredWeights)
                assertTrue("$name missing italic w$weight", weight to FontStyle.Italic in coveredWeights)
            }
        }
    }

    @Test
    fun `cormorant and fraunces body text falls back to Source Serif 4`() {
        assertSame(EditorialFonts.SourceSerif4, EditorialFaces.Cormorant.body)
        assertSame(EditorialFonts.SourceSerif4, EditorialFaces.Fraunces.body)
        // The self-paired faces keep their own body.
        assertSame(EditorialFonts.SourceSerif4, EditorialFaces.Source.body)
        assertSame(EditorialFonts.EBGaramond, EditorialFaces.Garamond.body)
    }

    @Test
    fun `every bundled font resource loads as a real typeface`() {
        val context = RuntimeEnvironment.getApplication()
        val allResIds = expectedResIdsByFamily.values.flatMap { listOf(it.second, it.third) }
        assertEquals(10, allResIds.distinct().size)
        for (resId in allResIds) {
            assertNotNull(
                "font resource ${context.resources.getResourceEntryName(resId)} must load",
                ResourcesCompat.getFont(context, resId),
            )
        }
    }

    @Test
    fun `every face display and body family is one of the five bundled families`() {
        val bundled: Set<FontFamily> = expectedResIdsByFamily.values.map { it.first }.toSet()
        for (face in EditorialFaces.All) {
            assertTrue("${face.key}.display must be bundled", face.display in bundled)
            assertTrue("${face.key}.body must be bundled", face.body in bundled)
        }
    }

    @Test
    fun `unknown face keys resolve to the Source default`() {
        assertSame(EditorialFaces.Source, EditorialFaces.fromKey(null))
        assertSame(EditorialFaces.Source, EditorialFaces.fromKey("comic-sans"))
    }
}
