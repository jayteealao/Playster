package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import kotlin.math.ceil
import kotlin.math.max

/**
 * Reading body text: the face's body family at reading size with the
 * prototype's `text-wrap: pretty` approximated by [LineBreak.Paragraph].
 */
@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = text,
        style = tokens.type.body.copy(lineBreak = LineBreak.Paragraph),
        color = tokens.palette.ink,
        modifier = modifier,
    )
}

/**
 * Body paragraph opening with the signature drop cap (44/0.82/W500 display
 * serif) and true multi-line wrap beside it — the mock's CSS `float: left`.
 *
 * The installed Compose text stack has no float layout: the inline-content
 * primitive reserves a rectangular single-line hole only (source:
 * androidx.compose.ui.text.Placeholder(width, height, verticalAlign) in the
 * resolved ui-text-android:1.11.4 artifact — no float/initial-letter API in
 * its class listing), so this is a custom [Layout]: the cap glyph and the
 * first `ceil(capBottom / bodyLineHeight)` body lines are measured with a
 * [rememberTextMeasurer] at reduced width beside the cap (6dp gap, 4dp cap
 * top offset per the mock), the remainder re-measured at full width below,
 * and all three [TextLayoutResult]s drawn directly — what is measured is
 * exactly what is drawn, so the split can never re-wrap differently.
 *
 * The split is deterministic (single measure pass per constraint change,
 * nothing async). Full text is exposed to accessibility as one node.
 */
@Composable
fun DropcapBody(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.length < 2) {
        BodyText(text, modifier)
        return
    }
    val tokens = LocalEditorialTokens.current
    val measurer = rememberTextMeasurer()
    val capStyle = remember(tokens) { tokens.type.dropcap.copy(color = tokens.palette.ink) }
    // Trim.None keeps the first line's top half-leading, so every line box
    // is the same height — the CSS line-box model the mock's float wrap is
    // computed against (Compose's default trims the first line ~5px short,
    // which shifts every subsequent line top relative to the browser).
    val bodyStyle =
        remember(tokens) {
            tokens.type.body.copy(
                color = tokens.palette.ink,
                lineBreak = LineBreak.Paragraph,
                lineHeightStyle =
                    LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Proportional,
                        trim = LineHeightStyle.Trim.None,
                    ),
            )
        }
    // Plain holder, written at measure time and read at draw time within the
    // same frame — deliberately not snapshot state (no invalidation needed;
    // draw always follows measure).
    val holder = remember { DropcapPass() }

    Layout(
        content = {},
        modifier =
            modifier
                .semantics { this.text = AnnotatedString(text) }
                .drawBehind {
                    holder.cap?.let { drawText(it, topLeft = Offset(0f, holder.capTop)) }
                    holder.beside?.let { beside ->
                        clipRect(top = 0f, bottom = holder.besideClip) {
                            drawText(beside, topLeft = Offset(holder.besideX, 0f))
                        }
                    }
                    holder.below?.let { drawText(it, topLeft = Offset(0f, holder.belowTop)) }
                },
    ) { _, constraints ->
        val cap = measurer.measure(AnnotatedString(text.take(1)), style = capStyle)
        val capTopPx = CAP_TOP_OFFSET.toPx()
        val gapPx = CAP_GAP.roundToPx()
        // CSS line-box math: the mock's cap occupies a 0.82em line box, and
        // the glyph's natural (ascent+descent) box centers on it via
        // half-leading. Compose's measured height keeps the full font box,
        // so the visual bottom comes from the ramp's own line-height — not
        // from the measured layout height.
        val capLineHeightPx =
            if (capStyle.lineHeight.type == TextUnitType.Em) {
                capStyle.fontSize.toPx() * capStyle.lineHeight.value
            } else {
                cap.size.height.toFloat()
            }
        val capBottom = capTopPx + capLineHeightPx
        val rest = text.drop(1)

        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else DEFAULT_UNBOUNDED_WIDTH.roundToPx()
        val besideWidth = (maxWidth - cap.size.width - gapPx).coerceAtLeast(1)
        val beside =
            measurer.measure(
                AnnotatedString(rest),
                style = bodyStyle,
                constraints = Constraints(maxWidth = besideWidth),
            )
        // CSS float rule: a line wraps beside the cap while its top sits
        // above the float's bottom; the first line whose top passes it
        // clears to full width. The epsilon absorbs pixel rounding of the
        // line boxes (the mock's 40.3-vs-40.08 boundary must clear).
        val snapEpsilonPx = 1.dp.toPx()
        var indentedLines = 1
        while (
            indentedLines < beside.lineCount &&
            beside.getLineTop(indentedLines) < capBottom - snapEpsilonPx
        ) {
            indentedLines++
        }

        holder.cap = cap
        // Center the measured glyph box on the CSS line box (half-leading).
        holder.capTop = capTopPx + (capLineHeightPx - cap.size.height) / 2f
        holder.besideX = (cap.size.width + gapPx).toFloat()
        holder.beside = beside

        val totalHeight: Int
        if (beside.lineCount <= indentedLines) {
            holder.besideClip = beside.size.height.toFloat()
            holder.below = null
            totalHeight = max(capBottom, beside.size.height.toFloat()).toInt()
        } else {
            val clipY = beside.getLineBottom(indentedLines - 1)
            val splitOffset = beside.getLineEnd(indentedLines - 1, visibleEnd = false)
            val below =
                measurer.measure(
                    AnnotatedString(rest.substring(splitOffset)),
                    style = bodyStyle,
                    constraints = Constraints(maxWidth = maxWidth),
                )
            holder.besideClip = clipY
            holder.below = below
            holder.belowTop = clipY
            totalHeight = (clipY + below.size.height).toInt()
        }
        layout(maxWidth, max(totalHeight, ceil(capBottom).toInt())) {}
    }
}

/** One measure pass's layouts and offsets, consumed by the draw phase. */
private class DropcapPass {
    var cap: TextLayoutResult? = null
    var capTop: Float = 0f
    var besideX: Float = 0f
    var besideClip: Float = 0f
    var beside: TextLayoutResult? = null
    var below: TextLayoutResult? = null
    var belowTop: Float = 0f
}

/** The mock's float geometry: margin-right 6, margin-top 4 on the cap. */
private val CAP_GAP = 6.dp
private val CAP_TOP_OFFSET = 4.dp

/** Fallback measuring width when a caller gives unbounded constraints. */
private val DEFAULT_UNBOUNDED_WIDTH = 368.dp
