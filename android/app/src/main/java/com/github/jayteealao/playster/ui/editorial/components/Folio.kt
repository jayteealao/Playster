package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * Folio — the persistent "page furniture" bar with the current page/episode
 * info: tracked-uppercase 9.5/W600/ls1.4 inkFaint slots at the prototype's
 * 8x22 padding, optionally bounded by a faint top rule (as the Player and
 * Playlist screens draw it).
 */
@Composable
fun Folio(
    left: String,
    right: String,
    modifier: Modifier = Modifier,
    topRule: Boolean = false,
) {
    val tokens = LocalEditorialTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (topRule) {
            EditorialRule(color = tokens.palette.ruleFaint)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(left.uppercase(), style = tokens.type.folio, color = tokens.palette.inkFaint)
            Text(right.uppercase(), style = tokens.type.folio, color = tokens.palette.inkFaint)
        }
    }
}
