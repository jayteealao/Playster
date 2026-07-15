package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/*
 * The editorial gap states: loading, empty, error, and the quota notice —
 * rule-bounded, italic-serif, and deliberately free of Material vocabulary
 * (no spinner, no snackbar, anywhere in these trees). All copy comes from
 * the caller: an empty state must *name what will appear* and offer a path
 * forward; an error must be *specific and actionable*. Screen slices feed
 * their sealed states into these primitives.
 */

/**
 * Loading — a static italic-serif line between rules. No motion by design:
 * the visual contract records no motion in this slice, and the editorial
 * voice treats waiting as a quiet sentence, not a spinner.
 */
@Composable
fun EditorialLoadingNotice(
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        EditorialRule()
        Text(
            text = label,
            style = tokens.type.deck,
            color = tokens.palette.inkSoft,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
        )
        EditorialRule(color = tokens.palette.ruleFaint)
    }
}

/**
 * Empty — teaches and guides, never scolds: a serif title, an italic line
 * that names what will appear here, and an action that leads somewhere.
 */
@Composable
fun EditorialEmptyNotice(
    title: String,
    teaches: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        EditorialRule()
        Spacer(Modifier.height(14.dp))
        DisplayTitle(text = title, sizeSp = EMPTY_TITLE_SP)
        Spacer(Modifier.height(6.dp))
        Deck(text = teaches)
        Spacer(Modifier.height(4.dp))
        EditorialTextAction(
            text = actionLabel,
            onClick = onAction,
            icon = EditorialIcons.Next,
        )
        Spacer(Modifier.height(6.dp))
        EditorialRule(color = tokens.palette.ruleFaint)
    }
}

/**
 * Error — a 2dp accent top rule (the accent as state indicator, within the
 * contract's accent-mark weight allowance), the caller's *specific* message
 * in the italic editorial voice, and an actionable retry slot.
 */
@Composable
fun EditorialErrorNotice(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        EditorialRule(color = tokens.accent.color, thickness = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = tokens.type.deck,
            color = tokens.palette.ink,
        )
        Spacer(Modifier.height(4.dp))
        EditorialTextAction(text = actionLabel, onClick = onAction)
        Spacer(Modifier.height(6.dp))
        EditorialRule(color = tokens.palette.ruleFaint)
    }
}

/**
 * Quota notice — the banner idiom re-skinned as a paperDeep band between
 * rules; informational, so it carries no action of its own.
 */
@Composable
fun EditorialQuotaNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        EditorialRule()
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(tokens.palette.paperDeep)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = message,
                style = tokens.type.deck,
                color = tokens.palette.ink,
            )
        }
        EditorialRule()
    }
}

private const val EMPTY_TITLE_SP = 19.0
