package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * Pull quote — large italic display serif (18/1.2/-0.2) above the content
 * flow, bounded by a 1dp top rule, with curly quotation marks added by the
 * component (the caller passes bare text). Vertical margins 12/14 and the
 * 10dp rule gap are the prototype's own.
 */
@Composable
fun PullQuote(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalEditorialTokens.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 14.dp),
    ) {
        EditorialRule()
        Spacer(Modifier.height(10.dp))
        Text(
            text = "“$text”",
            style = tokens.type.pullQuote.copy(lineBreak = LineBreak.Heading),
            color = tokens.palette.ink,
        )
    }
}
