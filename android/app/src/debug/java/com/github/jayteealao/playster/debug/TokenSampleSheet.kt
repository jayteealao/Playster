package com.github.jayteealao.playster.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * Debug-only token sample sheet: every editorial type ramp, rule weight,
 * color role, the highlight span, and the accent pair, rendered on the
 * current palette. This is the JVM screenshot subject and the cold-start
 * first-frame subject — it is not a product component.
 */
@Composable
fun TokenSampleSheet() {
    val tokens = LocalEditorialTokens.current
    val palette = tokens.palette
    val type = tokens.type

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.paper)
                .testTag("token-sample-sheet")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        // Kickers — tracked uppercase, never small-caps.
        Text("The Paper Trail".uppercase(), style = type.kicker, color = palette.inkSoft)
        Spacer(6)
        Text("Accent Kicker".uppercase(), style = type.kicker, color = tokens.accent.color)
        Spacer(6)
        Text("App Bar Kicker".uppercase(), style = type.appBarKicker, color = palette.inkSoft)
        Spacer(14)

        // Serif display + italic deck.
        Text("The Editorial Reader wears its ink well", style = type.display, color = palette.ink)
        Spacer(8)
        Text(
            "A deck in the body italic, setting the voice under the headline.",
            style = type.deck,
            color = palette.inkSoft,
        )
        Spacer(14)

        // Hairline rules: standard, faint, and the 2dp accent mark.
        Rule(palette.rule)
        Spacer(6)
        Rule(palette.ruleFaint)
        Spacer(6)
        Rule(tokens.accent.color, height = 2.dp)
        Spacer(14)

        // Drop cap + body with a highlight span (background behind text,
        // never a text color change).
        Row {
            Text("O", style = type.dropcap, color = palette.ink)
            Spacer(width = 6)
            Text(
                buildAnnotatedString {
                    append("nce the tokens are right, every screen after this is ")
                    withStyle(SpanStyle(background = palette.highlight)) {
                        append("already half right")
                    }
                    append(". Body text runs in the face's body family at reading size.")
                },
                style = type.body,
                color = palette.ink,
                modifier = Modifier.align(Alignment.Bottom),
            )
        }
        Spacer(14)

        // Pull quote — italic display serif above its top rule.
        Rule(palette.rule)
        Spacer(10)
        Text(
            "“Reading is just listening at your own speed.”",
            style = type.pullQuote,
            color = palette.ink,
        )
        Spacer(14)

        // Nav labels: active (ink + accent underline) vs inactive (faint).
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text("Reading", style = type.navLabel, color = palette.ink)
                Spacer(3)
                Rule(tokens.accent.color, height = 2.dp, modifier = Modifier.fillMaxWidth(0.15f))
            }
            Text("Library", style = type.navLabelInactive, color = palette.inkFaint)
            Text("Search", style = type.navLabelInactive, color = palette.inkFaint)
        }
        Spacer(14)

        // Paper wells + accent pair.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatch("paper", palette.paper, palette.ink, palette.rule)
            Swatch("deep", palette.paperDeep, palette.ink, palette.rule)
            Swatch("edge", palette.paperEdge, palette.ink, palette.rule)
        }
        Spacer(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatch("accent", tokens.accent.color, palette.paper, palette.rule)
            // Accent-on-soft: the soft fill is pale in every palette, so the
            // accent ink is the label color that stays readable on all four.
            Swatch("soft", tokens.accent.soft, tokens.accent.color, palette.rule)
            Swatch("highlight", palette.highlight, palette.ink, palette.rule)
        }
        Spacer(16)

        // Folio furniture.
        Rule(palette.ruleFaint)
        Spacer(8)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Playster".uppercase(), style = type.folio, color = palette.inkFaint)
            Text(
                "${palette.displayName} · ${tokens.face.displayName}".uppercase(),
                style = type.folio,
                color = palette.inkFaint,
            )
        }
    }
}

@Composable
private fun Rule(
    color: Color,
    height: androidx.compose.ui.unit.Dp = 1.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(color),
    )
}

@Composable
private fun Swatch(
    label: String,
    fill: Color,
    labelColor: Color,
    border: Color,
) {
    Box(
        modifier =
            Modifier
                .size(width = 96.dp, height = 44.dp)
                .background(fill)
                .border(1.dp, border),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = LocalEditorialTokens.current.type.folio, color = labelColor)
    }
}

@Composable
private fun Spacer(
    height: Int = 0,
    width: Int = 0,
) {
    Box(Modifier.size(width = width.dp, height = height.dp))
}
