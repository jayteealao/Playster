package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.EditorialTokens
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.edScaled

/*
 * The editorial typographic primitives: each pairs a token ramp style with
 * its palette role exactly as the design prototype draws it. None of them
 * carries layout padding — spacing is the caller's (screen's) concern.
 */

/**
 * Tracked-uppercase kicker — wayfinding above headlines and sections.
 * Uppercasing happens here (tracked uppercase, never small-caps).
 * 9.5sp/W700/ls1.6 is the PO-ratified contrast exception: mock-exact
 * fidelity wins over the type-size floor at exactly this size (documented
 * for the verify exception list).
 */
@Composable
fun Kicker(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text.uppercase(),
        style = tokens.type.kicker,
        color = if (accent) tokens.accent.color else tokens.palette.inkSoft,
        modifier = modifier,
    )
}

/**
 * Serif display title. [sizeSp] mirrors the prototype's size prop
 * (32/26/22/19/16 across screens); balanced wrapping approximates the
 * mock's `text-wrap: balance`.
 */
@Composable
fun DisplayTitle(
    text: String,
    modifier: Modifier = Modifier,
    sizeSp: Double = DISPLAY_DEFAULT_SP,
    italic: Boolean = false,
) {
    DisplayTitle(AnnotatedString(text), modifier, sizeSp, italic)
}

/** [AnnotatedString] overload for titles with italic spans, as the mock sets them. */
@Composable
fun DisplayTitle(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    sizeSp: Double = DISPLAY_DEFAULT_SP,
    italic: Boolean = false,
) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style =
            tokens.type.display.copy(
                fontSize = edScaled(sizeSp, tokens.sizeStep.multiplier).sp,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                lineBreak = LineBreak.Heading,
            ),
        color = tokens.palette.ink,
        modifier = modifier,
    )
}

/** Italic deck — sits under a display title and sets the voice. */
@Composable
fun Deck(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style = tokens.type.deck,
        color = tokens.palette.inkSoft,
        modifier = modifier,
    )
}

/** Italic byline/dateline — serif body 11, inkSoft ("by {channel} · {hours}"). */
@Composable
fun Dateline(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style = tokens.datelineStyle(),
        color = tokens.palette.inkSoft,
        modifier = modifier,
    )
}

/**
 * Italic serif ordinal with tabular numerals — the "01"/"02" list counters.
 * [sizeSp] is 13 on the home shelf and 12 in episode lists; [active] swaps
 * inkFaint for the accent (the playing row treatment).
 */
@Composable
fun Ordinal(
    text: String,
    modifier: Modifier = Modifier,
    sizeSp: Double = ORDINAL_SHELF_SP,
    active: Boolean = false,
) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style = tokens.ordinalStyle(sizeSp),
        color = if (active) tokens.accent.color else tokens.palette.inkFaint,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

private const val DISPLAY_DEFAULT_SP = 26.0
private const val ORDINAL_SHELF_SP = 13.0
private const val DATELINE_SP = 11.0

/** Ordinal: the display serif in italic with tabular figures (mock: ed.serif). */
internal fun EditorialTokens.ordinalStyle(sizeSp: Double): TextStyle =
    TextStyle(
        fontFamily = face.display,
        fontSize = edScaled(sizeSp, sizeStep.multiplier).sp,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.W400,
        fontFeatureSettings = TABULAR_NUMS,
    )

/** Dateline: the deck voice a step smaller, natural leading. */
internal fun EditorialTokens.datelineStyle(): TextStyle =
    type.deck.copy(
        fontSize = edScaled(DATELINE_SP, sizeStep.multiplier).sp,
        lineHeight = TextUnit.Unspecified,
    )

/** OpenType tabular figures — equal-width digits for counters and timestamps. */
internal const val TABULAR_NUMS = "tnum"
