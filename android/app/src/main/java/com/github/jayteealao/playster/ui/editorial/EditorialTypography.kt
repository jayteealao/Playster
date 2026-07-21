package com.github.jayteealao.playster.ui.editorial

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.R
import kotlin.math.roundToLong

/**
 * The five bundled families — all variable TTFs vendored from google/fonts —
 * resolved strictly from res/font (no downloadable-fonts path anywhere).
 *
 * Each requested weight maps to the same variable file with an explicit wght
 * axis setting, so w400..w700 all instantiate from one asset per style.
 */
object EditorialFonts {
    private val RAMP_WEIGHTS =
        listOf(
            FontWeight.W400,
            FontWeight.W500,
            FontWeight.W600,
            FontWeight.W700,
        )

    // Font(resId, weight, style, variationSettings) is @ExperimentalTextApi in
    // the installed artifact (source: gradle cache androidx.compose.ui:
    // ui-text-android:1.11.4 ui-text.aar -> FontKt.Font-F3nL8kk). The explicit
    // wght setting is the point of bundling variable TTFs; the API has been
    // stable-in-behavior since 1.4 and the fixture + goldens gate regressions.
    @OptIn(ExperimentalTextApi::class)
    private fun variableFamily(
        uprightRes: Int,
        italicRes: Int,
    ): FontFamily =
        FontFamily(
            RAMP_WEIGHTS.flatMap { weight ->
                listOf(
                    Font(
                        resId = uprightRes,
                        weight = weight,
                        style = FontStyle.Normal,
                        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
                    ),
                    Font(
                        resId = italicRes,
                        weight = weight,
                        style = FontStyle.Italic,
                        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
                    ),
                )
            },
        )

    val InterTight: FontFamily = variableFamily(R.font.inter_tight_var, R.font.inter_tight_italic_var)
    val SourceSerif4: FontFamily = variableFamily(R.font.source_serif4_var, R.font.source_serif4_italic_var)
    val EBGaramond: FontFamily = variableFamily(R.font.eb_garamond_var, R.font.eb_garamond_italic_var)
    val Fraunces: FontFamily = variableFamily(R.font.fraunces_var, R.font.fraunces_italic_var)
    val CormorantGaramond: FontFamily =
        variableFamily(R.font.cormorant_garamond_var, R.font.cormorant_garamond_italic_var)
}

/**
 * A display face selection: the serif that carries headlines plus the body
 * family it pairs with. Cormorant and Fraunces read beautifully large but not
 * small, so — exactly as the prototype's FACES table encodes — their body
 * text falls back to Source Serif 4.
 */
@Immutable
data class EditorialFace(
    val key: String,
    val displayName: String,
    val display: FontFamily,
    val body: FontFamily,
)

object EditorialFaces {
    val Source =
        EditorialFace(
            "source",
            "Source Serif",
            EditorialFonts.SourceSerif4,
            EditorialFonts.SourceSerif4,
        )
    val Garamond =
        EditorialFace(
            "garamond",
            "EB Garamond",
            EditorialFonts.EBGaramond,
            EditorialFonts.EBGaramond,
        )
    val Cormorant =
        EditorialFace(
            "cormorant",
            "Cormorant",
            EditorialFonts.CormorantGaramond,
            EditorialFonts.SourceSerif4,
        )
    val Fraunces =
        EditorialFace(
            "fraunces",
            "Fraunces",
            EditorialFonts.Fraunces,
            EditorialFonts.SourceSerif4,
        )

    val All: List<EditorialFace> = listOf(Source, Garamond, Cormorant, Fraunces)

    /** Unknown or absent keys resolve to Source Serif, the prototype default. */
    fun fromKey(key: String?): EditorialFace = All.firstOrNull { it.key == key } ?: Source
}

/** Type size steps from the prototype's tweaks panel. */
enum class SizeStep(val key: String, val label: String, val multiplier: Double) {
    S("s", "S", 0.9),
    M("m", "M", 1.0),
    L("l", "L", 1.1),
    XL("xl", "XL", 1.2),
    ;

    companion object {
        fun fromKey(key: String?): SizeStep = entries.firstOrNull { it.key == key } ?: M
    }
}

/**
 * Reading line-height steps. Comfortable (the default) reproduces the
 * prototype's line heights exactly; the prototype's Settings mock names the
 * axis without enumerating the other steps, so Tight/Airy are this port's
 * values and only scale running body text.
 */
enum class LineHeightStep(val key: String, val label: String, val multiplier: Double) {
    TIGHT("tight", "Tight", 0.92),
    COMFORTABLE("comfortable", "Comfortable", 1.0),
    AIRY("airy", "Airy", 1.12),
    ;

    companion object {
        fun fromKey(key: String?): LineHeightStep = entries.firstOrNull { it.key == key } ?: COMFORTABLE
    }
}

/** Density steps — defined per the prototype's tweaks panel; v1 ships Default only. */
enum class DensityStep(val key: String, val label: String, val multiplier: Double) {
    COMPACT("compact", "Compact", 0.85),
    DEFAULT("default", "Default", 1.0),
    ROOMY("roomy", "Roomy", 1.18),
    ;

    companion object {
        fun fromKey(key: String?): DensityStep = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The prototype's `ed.s()` size arithmetic: multiply, then round to two
 * decimals — computed in doubles so the result matches the mock's JavaScript
 * arithmetic bit-for-bit before the sp conversion.
 */
private const val TWO_DECIMALS = 100.0

fun edScaled(
    base: Double,
    multiplier: Double,
): Float = ((base * multiplier * TWO_DECIMALS).roundToLong() / TWO_DECIMALS).toFloat()

/**
 * The nine editorial type ramps, resolved for one face + size/line-height
 * step selection. Sizes are sp (respecting system font scale), line heights
 * are em multipliers of the resolved size, letter spacing is sp (the mock's
 * tracking is absolute and does not scale with the size step).
 *
 * Ramps carry no color: components pair them with palette roles (kicker over
 * inkSoft, folio over inkFaint, ...) so one ramp works on all four papers.
 * Kickers and folios are *tracked uppercase* — the caller uppercases the
 * string; never use small-caps font features (GSUB degradation risk).
 */
@Immutable
data class EditorialTypeRamp internal constructor(
    val kicker: TextStyle,
    val appBarKicker: TextStyle,
    val display: TextStyle,
    val deck: TextStyle,
    val body: TextStyle,
    val dropcap: TextStyle,
    val pullQuote: TextStyle,
    val folio: TextStyle,
    val navLabel: TextStyle,
    val navLabelInactive: TextStyle,
)

/** One ramp's mock-sourced numbers: base px size, weight, leading, tracking. */
private data class RampSpec(
    val size: Double,
    val weight: FontWeight,
    val lineHeightEm: Double? = null,
    val trackingSp: Double? = null,
    val italic: Boolean = false,
)

// The nine primitives exactly as theme.jsx draws them (fixture-tested).
private val KICKER_SPEC = RampSpec(size = 9.5, weight = FontWeight.W700, trackingSp = 1.6)
private val APP_BAR_KICKER_SPEC = RampSpec(size = 10.0, weight = FontWeight.W600, trackingSp = 1.6)
private val DISPLAY_SPEC = RampSpec(size = 26.0, weight = FontWeight.W400, lineHeightEm = 1.05, trackingSp = -0.4)
private val DECK_SPEC = RampSpec(size = 12.0, weight = FontWeight.W400, lineHeightEm = 1.4, italic = true)
private val BODY_SPEC = RampSpec(size = 13.0, weight = FontWeight.W400, lineHeightEm = 1.55)
private val DROPCAP_SPEC = RampSpec(size = 44.0, weight = FontWeight.W500, lineHeightEm = 0.82)
private val PULL_QUOTE_SPEC =
    RampSpec(size = 18.0, weight = FontWeight.W400, lineHeightEm = 1.2, trackingSp = -0.2, italic = true)
private val FOLIO_SPEC = RampSpec(size = 9.5, weight = FontWeight.W600, trackingSp = 1.4)
private val NAV_LABEL_SPEC = RampSpec(size = 11.5, weight = FontWeight.W700, trackingSp = 0.2)
private val NAV_LABEL_INACTIVE_SPEC = NAV_LABEL_SPEC.copy(weight = FontWeight.W500)

private fun RampSpec.toStyle(
    family: FontFamily,
    sizeMultiplier: Double,
    lineHeightMultiplier: Double = 1.0,
): TextStyle =
    TextStyle(
        fontFamily = family,
        fontSize = edScaled(size, sizeMultiplier).sp,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        lineHeight = lineHeightEm?.let { (it * lineHeightMultiplier).em } ?: TextUnit.Unspecified,
        letterSpacing = trackingSp?.sp ?: TextUnit.Unspecified,
    )

fun editorialTypeRamp(
    face: EditorialFace,
    sizeStep: SizeStep = SizeStep.M,
    lineHeightStep: LineHeightStep = LineHeightStep.COMFORTABLE,
    sans: FontFamily = EditorialFonts.InterTight,
): EditorialTypeRamp {
    val s = sizeStep.multiplier
    return EditorialTypeRamp(
        kicker = KICKER_SPEC.toStyle(sans, s),
        appBarKicker = APP_BAR_KICKER_SPEC.toStyle(sans, s),
        display = DISPLAY_SPEC.toStyle(face.display, s),
        deck = DECK_SPEC.toStyle(face.body, s),
        // Body is the one reading style the line-height preference scales.
        body = BODY_SPEC.toStyle(face.body, s, lineHeightStep.multiplier),
        dropcap = DROPCAP_SPEC.toStyle(face.display, s),
        pullQuote = PULL_QUOTE_SPEC.toStyle(face.display, s),
        folio = FOLIO_SPEC.toStyle(sans, s),
        navLabel = NAV_LABEL_SPEC.toStyle(sans, s),
        navLabelInactive = NAV_LABEL_INACTIVE_SPEC.toStyle(sans, s),
    )
}
