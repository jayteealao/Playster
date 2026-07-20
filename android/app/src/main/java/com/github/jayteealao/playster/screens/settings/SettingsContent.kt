@file:Suppress("TooManyFunctions")

package com.github.jayteealao.playster.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens
import com.github.jayteealao.playster.ui.editorial.components.Deck
import com.github.jayteealao.playster.ui.editorial.components.DisplayTitle
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialPillButton
import com.github.jayteealao.playster.ui.editorial.components.EditorialRule
import com.github.jayteealao.playster.ui.editorial.components.Kicker
import com.github.jayteealao.playster.ui.editorial.edScaled

/**
 * The stateless Settings composition — the pixel-gate + golden subject. A profile
 * masthead over the 3-up stats rule, then the live reading axes (each a value row
 * that expands to option pills), the default-speed row, the account sign-out, the
 * version line, and the bundled OSS notices. Primitives only; no Material
 * vocabulary. Every selectable surface carries an `id:` testTag for the flow.
 *
 * The two mock rows that cannot ship — Background play and Skip silences (YouTube
 * ToS), plus the Library section (Download over Wi-Fi / Storage) and the
 * Highlights toggle (no offline, no stubbed UI) — are deliberately absent; the
 * account section is the derived addition. These are the enumerated pixel-gate
 * deviations (AC6).
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onSelectAxis: (SettingsAxis, String) -> Unit,
    onSetDefaultSpeed: (Float) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.palette.paper)
                .verticalScroll(rememberScrollState())
                .testTag("settings-content"),
    ) {
        EditorialAppBar(kicker = "You · Reader Preferences")
        ProfileMasthead(state.profile, state.stats)
        Column(modifier = Modifier.padding(start = SCREEN_HORIZONTAL, end = SCREEN_HORIZONTAL, bottom = 16.dp)) {
            SectionKicker("Reading", topPadding = 8.dp)
            state.readingAxes.forEach { row -> AxisPrefRow(row = row, onSelect = onSelectAxis) }

            SectionKicker("Playback")
            SpeedPrefRow(row = state.defaultSpeed, onSetDefaultSpeed = onSetDefaultSpeed)

            SectionKicker("Account")
            AccountRow(onSignOut = onSignOut)

            VersionLine(state.version)
            if (state.licenses.isNotEmpty()) {
                LicensesRow(state.licenses)
            }
        }
    }
}

private val SCREEN_HORIZONTAL = 22.dp

@Composable
private fun ProfileMasthead(
    profile: SettingsUiState.Profile,
    stats: List<SettingsUiState.Stat>,
) {
    Column(
        modifier =
            Modifier.padding(
                start = SCREEN_HORIZONTAL,
                end = SCREEN_HORIZONTAL,
                top = 4.dp,
                bottom = 14.dp,
            ),
    ) {
        Kicker(text = profile.sinceLabel, accent = true)
        Spacer(Modifier.height(6.dp))
        DisplayTitle(text = profile.name, sizeSp = 32.0)
        Spacer(Modifier.height(8.dp))
        Deck(text = profile.deck)
        if (stats.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            StatsRule(stats)
        }
    }
}

/** The 3-up stats rule: top + bottom hairline, vertical dividers between cells. */
@Composable
private fun StatsRule(stats: List<SettingsUiState.Stat>) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = Modifier.testTag("settings-stats")) {
        EditorialRule()
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            stats.forEachIndexed { index, stat ->
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stat.value,
                        style =
                            TextStyle(
                                fontFamily = tokens.face.display,
                                fontSize = edScaled(STAT_VALUE_SP, tokens.sizeStep.multiplier).sp,
                                lineHeight = 1.em,
                            ),
                        color = tokens.palette.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stat.caption.uppercase(),
                        style =
                            TextStyle(
                                fontFamily = tokens.sans,
                                fontSize = edScaled(STAT_CAPTION_SP, tokens.sizeStep.multiplier).sp,
                                fontWeight = FontWeight.W600,
                                letterSpacing = 0.6.sp,
                            ),
                        color = tokens.palette.inkFaint,
                    )
                }
                if (index < stats.lastIndex) {
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(tokens.palette.rule))
                }
            }
        }
        EditorialRule()
    }
}

@Composable
private fun SectionKicker(
    text: String,
    topPadding: Dp = 18.dp,
) {
    Kicker(
        text = text,
        accent = true,
        modifier = Modifier.padding(top = topPadding, bottom = 4.dp),
    )
}

@Composable
private fun AxisPrefRow(
    row: SettingsUiState.AxisRow,
    onSelect: (SettingsAxis, String) -> Unit,
) {
    var expanded by rememberSaveable(row.axis) { mutableStateOf(false) }
    Column {
        PrefRow(
            title = row.title,
            value = row.currentLabel,
            testTag = "settings-row-${row.axis.tag}",
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            OptionPills(
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                row.options.forEach { option ->
                    OptionPill(
                        label = option.label,
                        selected = option.selected,
                        modifier = Modifier.testTag("settings-option-${row.axis.tag}-${option.key}"),
                        onClick = { onSelect(row.axis, option.key) },
                    )
                }
            }
        }
        EditorialRule()
    }
}

@Composable
private fun SpeedPrefRow(
    row: SettingsUiState.SpeedRow,
    onSetDefaultSpeed: (Float) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        PrefRow(
            title = "Default speed",
            value = row.currentLabel,
            testTag = "settings-row-speed",
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            OptionPills(modifier = Modifier.padding(bottom = 12.dp)) {
                row.options.forEach { option ->
                    OptionPill(
                        label = option.label,
                        selected = option.selected,
                        modifier = Modifier.testTag("settings-option-speed-${option.label.trimEnd('×')}"),
                        onClick = { onSetDefaultSpeed(option.rate) },
                    )
                }
            }
        }
        EditorialRule()
    }
}

/** A label + current-value + chevron row — the mock's baseline-aligned pref row. */
@Composable
private fun PrefRow(
    title: String,
    value: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(testTag)
                .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style =
                TextStyle(
                    fontFamily = tokens.face.body,
                    fontSize = edScaled(ROW_TITLE_SP, tokens.sizeStep.multiplier).sp,
                ),
            color = tokens.palette.ink,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style =
                TextStyle(
                    fontFamily = tokens.sans,
                    fontSize = edScaled(ROW_VALUE_SP, tokens.sizeStep.multiplier).sp,
                    letterSpacing = 0.2.sp,
                ),
            color = tokens.palette.inkSoft,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = EditorialIcons.Next,
            contentDescription = null,
            tint = tokens.palette.inkFaint,
            modifier = Modifier.size(11.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionPills(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/**
 * A selectable option pill: the recent-search chip idiom with a selected state.
 * Selected reuses the ink-fill / paper-text button treatment so contrast holds on
 * every palette (including Night); unselected is the rule-bordered chip.
 */
@Composable
private fun OptionPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalEditorialTokens.current
    val style =
        TextStyle(
            fontFamily = tokens.face.body,
            fontStyle = FontStyle.Italic,
            fontSize = edScaled(PILL_LABEL_SP, tokens.sizeStep.multiplier).sp,
        )
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .then(if (selected) Modifier.background(tokens.palette.ink) else Modifier)
                .border(
                    width = 1.dp,
                    color = if (selected) tokens.palette.ink else tokens.palette.rule,
                    shape = CircleShape,
                )
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = label, style = style, color = if (selected) tokens.palette.paper else tokens.palette.ink)
    }
}

@Composable
private fun AccountRow(onSignOut: () -> Unit) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = Modifier.padding(vertical = 13.dp)) {
        Text(
            text = "Sign out of your Google account. Your reading data stays synced.",
            style = tokens.type.deck,
            color = tokens.palette.inkSoft,
        )
        Spacer(Modifier.height(10.dp))
        EditorialPillButton(
            text = "Sign out",
            onClick = onSignOut,
            modifier = Modifier.testTag("settings-sign-out"),
        )
    }
}

@Composable
private fun VersionLine(version: String) {
    val tokens = LocalEditorialTokens.current
    Text(
        text = version,
        style =
            TextStyle(
                fontFamily = tokens.face.body,
                fontStyle = FontStyle.Italic,
                fontSize = edScaled(VERSION_SP, tokens.sizeStep.multiplier).sp,
            ),
        color = tokens.palette.inkFaint,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp).testTag("settings-version"),
    )
}

@Composable
private fun LicensesRow(licenses: List<SettingsUiState.LicenseEntry>) {
    val tokens = LocalEditorialTokens.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Spacer(Modifier.height(4.dp))
        PrefRow(
            title = "Open-source licenses",
            value = if (expanded) "Hide" else "${licenses.size} fonts",
            testTag = "settings-licenses-row",
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 12.dp).testTag("settings-licenses-body")) {
                licenses.forEach { entry ->
                    Kicker(text = entry.name)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.text,
                        style =
                            tokens.type.body.copy(
                                fontSize = edScaled(LICENSE_SP, tokens.sizeStep.multiplier).sp,
                            ),
                        color = tokens.palette.inkSoft,
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
        EditorialRule()
    }
}

/** Stable `id:` fragment per axis for the Maestro selectors. */
private val SettingsAxis.tag: String
    get() =
        when (this) {
            SettingsAxis.FACE -> "face"
            SettingsAxis.SIZE -> "size"
            SettingsAxis.LINE_HEIGHT -> "line-height"
            SettingsAxis.PAPER -> "paper"
        }

private const val STAT_VALUE_SP = 20.0
private const val STAT_CAPTION_SP = 9.0
private const val ROW_TITLE_SP = 14.0
private const val ROW_VALUE_SP = 11.0
private const val PILL_LABEL_SP = 11.0
private const val VERSION_SP = 11.0
private const val LICENSE_SP = 10.0
