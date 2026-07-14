package com.github.jayteealao.playster.ui.editorial

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.core.content.ContextCompat
import com.github.jayteealao.playster.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC: every production token equals the fixture extracted from the design
 * prototype's own theme source — byte-equal, not approximately faithful.
 * Regenerate the fixture with: node android/scripts/extract-theme-fixture.mjs
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorialTokenFixtureTest {
    private val productionPalettes =
        mapOf(
            "cream" to EditorialPalettes.Cream,
            "vellum" to EditorialPalettes.Vellum,
            "newsprint" to EditorialPalettes.Newsprint,
            "night" to EditorialPalettes.Night,
        )

    private val productionAccents =
        mapOf(
            "oxblood" to EditorialAccents.Oxblood,
            "forest" to EditorialAccents.Forest,
            "slate" to EditorialAccents.Slate,
            "ink" to EditorialAccents.Ink,
        )

    @Test
    fun `every palette color role matches the mock fixture exactly`() {
        assertEquals(EditorialThemeFixture.palettes.keys, productionPalettes.keys)
        for ((key, fixture) in EditorialThemeFixture.palettes) {
            val palette = productionPalettes.getValue(key)
            assertArgb("$key.paper", fixture.paper, palette.paper)
            assertArgb("$key.paperDeep", fixture.paperDeep, palette.paperDeep)
            assertArgb("$key.paperEdge", fixture.paperEdge, palette.paperEdge)
            assertArgb("$key.ink", fixture.ink, palette.ink)
            assertArgb("$key.inkSoft", fixture.inkSoft, palette.inkSoft)
            assertArgb("$key.inkFaint", fixture.inkFaint, palette.inkFaint)
            assertArgb("$key.rule", fixture.rule, palette.rule)
            assertArgb("$key.ruleFaint", fixture.ruleFaint, palette.ruleFaint)
            assertArgb("$key.highlight", fixture.highlight, palette.highlight)
            assertEquals("$key.dark", fixture.dark, palette.dark)
        }
    }

    @Test
    fun `every accent matches the mock fixture exactly`() {
        assertEquals(EditorialThemeFixture.accents.keys, productionAccents.keys)
        for ((key, fixture) in EditorialThemeFixture.accents) {
            val accent = productionAccents.getValue(key)
            assertArgb("$key.color", fixture.color, accent.color)
            assertArgb("$key.soft", fixture.soft, accent.soft)
        }
    }

    @Test
    fun `token lint - no token is pure white or pure black`() {
        val allTokens =
            buildMap {
                for ((key, p) in productionPalettes) {
                    put("$key.paper", p.paper)
                    put("$key.paperDeep", p.paperDeep)
                    put("$key.paperEdge", p.paperEdge)
                    put("$key.ink", p.ink)
                    put("$key.inkSoft", p.inkSoft)
                    put("$key.inkFaint", p.inkFaint)
                    put("$key.rule", p.rule)
                    put("$key.ruleFaint", p.ruleFaint)
                    put("$key.highlight", p.highlight)
                }
                for ((key, a) in productionAccents) {
                    put("$key.color", a.color)
                    put("$key.soft", a.soft)
                }
            }
        for ((name, color) in allTokens) {
            assertNotEquals("$name must not be pure white", 0xFFFFFFFF.toInt(), color.toArgb())
            assertNotEquals("$name must not be pure black", 0xFF000000.toInt(), color.toArgb())
        }
    }

    @Test
    fun `window background resources equal the Kotlin paper tokens`() {
        val context: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val xmlPapers =
            mapOf(
                "cream" to R.color.editorial_paper_cream,
                "vellum" to R.color.editorial_paper_vellum,
                "newsprint" to R.color.editorial_paper_newsprint,
                "night" to R.color.editorial_paper_night,
            )
        for ((key, resId) in xmlPapers) {
            assertEquals(
                "editorial_paper_$key must equal the Kotlin token",
                productionPalettes.getValue(key).paper.toArgb(),
                ContextCompat.getColor(context, resId),
            )
        }
    }

    @Test
    fun `type ramps match the mock fixture at default steps`() {
        val ramp = editorialTypeRamp(EditorialFaces.Source)
        val styles =
            mapOf(
                "kicker" to ramp.kicker,
                "appBarKicker" to ramp.appBarKicker,
                "display" to ramp.display,
                "deck" to ramp.deck,
                "body" to ramp.body,
                "dropcap" to ramp.dropcap,
                "pullQuote" to ramp.pullQuote,
                "folio" to ramp.folio,
                "navLabel" to ramp.navLabel,
            )
        assertEquals(EditorialThemeFixture.ramps.keys, styles.keys)
        for ((name, fixture) in EditorialThemeFixture.ramps) {
            val style = styles.getValue(name)
            assertEquals("$name.fontSize", fixture.fontSize.toFloat(), style.fontSize.value, 0f)
            assertTrue("$name.fontSize must be sp", style.fontSize.type == androidx.compose.ui.unit.TextUnitType.Sp)
            assertLineHeight(name, fixture.lineHeight, style)
            assertLetterSpacing(name, fixture.letterSpacing, style)
            assertEquals("$name.fontWeight", fixture.fontWeight, style.fontWeight?.weight)
        }
        assertEquals(
            "navLabelInactive.fontWeight",
            EditorialThemeFixture.NAV_LABEL_INACTIVE_WEIGHT,
            ramp.navLabelInactive.fontWeight?.weight,
        )
    }

    @Test
    fun `size arithmetic reproduces the mock's edS for every ramp and step`() {
        for (step in SizeStep.entries) {
            for ((name, fixture) in EditorialThemeFixture.ramps) {
                assertEquals(
                    "$name at step ${step.key}",
                    EditorialThemeFixture.edS(fixture.fontSize, step.multiplier).toFloat(),
                    edScaled(fixture.fontSize, step.multiplier),
                    0f,
                )
            }
        }
        // Spot-check the scaled ramp itself: display at XL.
        val xl = editorialTypeRamp(EditorialFaces.Source, sizeStep = SizeStep.XL)
        assertEquals(
            EditorialThemeFixture.edS(EditorialThemeFixture.ramps.getValue("display").fontSize, 1.2).toFloat(),
            xl.display.fontSize.value,
            0f,
        )
    }

    @Test
    fun `step axes match the mock fixture`() {
        assertEquals(EditorialThemeFixture.sizeSteps, SizeStep.entries.map { it.multiplier })
        assertEquals(EditorialThemeFixture.densitySteps, DensityStep.entries.map { it.multiplier })
        // The line-height axis is not mock-derived (the prototype names the
        // preference without enumerating steps); Comfortable must be exactly 1
        // so the shipped default reproduces the mock's line heights.
        assertEquals(1.0, LineHeightStep.COMFORTABLE.multiplier, 0.0)
    }

    @Test
    fun `face pairings match the mock fixture`() {
        val familiesByMockName =
            mapOf(
                "Inter Tight" to EditorialFonts.InterTight,
                "Source Serif 4" to EditorialFonts.SourceSerif4,
                "EB Garamond" to EditorialFonts.EBGaramond,
                "Cormorant Garamond" to EditorialFonts.CormorantGaramond,
                "Fraunces" to EditorialFonts.Fraunces,
            )
        val productionFaces =
            mapOf(
                "source" to EditorialFaces.Source,
                "garamond" to EditorialFaces.Garamond,
                "cormorant" to EditorialFaces.Cormorant,
                "fraunces" to EditorialFaces.Fraunces,
            )
        assertEquals(EditorialThemeFixture.faces.keys, productionFaces.keys)
        for ((key, fixture) in EditorialThemeFixture.faces) {
            val face = productionFaces.getValue(key)
            assertSame("$key.display", familiesByMockName.getValue(fixture.displayFamily), face.display)
            assertSame("$key.body", familiesByMockName.getValue(fixture.bodyFamily), face.body)
        }
        // The wayfinding sans is the mock's SANS stack head.
        assertSame(
            familiesByMockName.getValue(EditorialThemeFixture.SANS_FAMILY),
            editorialTypeRamp(EditorialFaces.Source).kicker.fontFamily,
        )
    }

    private fun assertArgb(
        site: String,
        expected: Long,
        actual: Color,
    ) {
        assertEquals(site, expected.toInt(), actual.toArgb())
    }

    private fun assertLineHeight(
        name: String,
        expected: Double?,
        style: TextStyle,
    ) {
        if (expected == null) {
            assertTrue("$name.lineHeight must be unspecified", style.lineHeight.isUnspecified)
        } else {
            // Line heights are em multiples so they track the resolved size.
            assertTrue("$name.lineHeight must be em", style.lineHeight.type == androidx.compose.ui.unit.TextUnitType.Em)
            assertEquals("$name.lineHeight", expected.toFloat(), style.lineHeight.value, 0f)
        }
    }

    private fun assertLetterSpacing(
        name: String,
        expected: Double?,
        style: TextStyle,
    ) {
        val actual: TextUnit = style.letterSpacing
        if (expected == null) {
            assertTrue("$name.letterSpacing must be unspecified", actual.isUnspecified)
        } else {
            assertTrue("$name.letterSpacing must be sp", actual.type == androidx.compose.ui.unit.TextUnitType.Sp)
            assertEquals("$name.letterSpacing", expected.toFloat(), actual.value, 0f)
        }
    }
}
