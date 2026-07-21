package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * The editorial bottom navigation: a 1dp top rule over the paper field,
 * text-only items (the mock's Reading/Library/Search/You) spaced across the
 * prototype's 12-24-14 padding. The active item is ink W700 over a 1.5dp
 * accent underline — the accent is one of the design's few chromatic
 * events; inactive items are inkFaint W500. Tab-role selection semantics;
 * touch targets extend invisibly to the platform 48dp minimum.
 *
 * Navigation wiring is the chrome slice's concern — this is the visual
 * component with selection slots only.
 */
@Composable
fun EditorialBottomNav(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = modifier.fillMaxWidth().background(tokens.palette.paper)) {
        EditorialRule()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items.forEachIndexed { index, label ->
                val active = index == selectedIndex
                Column(
                    modifier =
                        Modifier
                            .width(IntrinsicSize.Min)
                            .selectable(
                                selected = active,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ),
                ) {
                    Text(
                        text = label,
                        style =
                            tokens.type.navLabel.copy(
                                fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
                            ),
                        color = if (active) tokens.palette.ink else tokens.palette.inkFaint,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.5.dp)
                                .background(if (active) tokens.accent.color else Color.Transparent),
                    )
                }
            }
        }
    }
}
