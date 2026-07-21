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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * Editorial underline tabs: sans 11.5, active ink W600 over a 1.5dp ink
 * underline that overlaps the row's 1dp bottom rule (the prototype's
 * `marginBottom: -1` idiom), inactive inkFaint W500. Selection semantics
 * per tab; the touch target expands invisibly to the platform's 48dp
 * minimum (visual density stays mock-exact).
 */
@Composable
fun EditorialTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 18.dp,
) {
    val tokens = LocalEditorialTokens.current
    Box(modifier = modifier.fillMaxWidth()) {
        EditorialRule(modifier = Modifier.align(Alignment.BottomStart))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            tabs.forEachIndexed { index, label ->
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
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style =
                            tokens.type.navLabelInactive.copy(
                                fontWeight = if (active) FontWeight.W600 else FontWeight.W500,
                            ),
                        color = if (active) tokens.palette.ink else tokens.palette.inkFaint,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.5.dp)
                                .background(if (active) tokens.palette.ink else Color.Transparent),
                    )
                }
            }
        }
    }
}
