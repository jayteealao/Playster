package com.github.jayteealao.playster.ui.editorial.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.LocalEditorialTokens

/**
 * The editorial app bar: 28dp leading/trailing slots around a centered
 * tracked-uppercase kicker (10/W600/ls1.6 inkSoft), at the prototype's
 * 6-14-4 padding. Empty slots keep their 28dp width so the kicker stays
 * optically centered. Use [EditorialAppBarAction] for slot buttons — each
 * carries a content description and the invisible 48dp touch extension.
 */
@Composable
fun EditorialAppBar(
    kicker: String,
    modifier: Modifier = Modifier,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalEditorialTokens.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBarSlot(left)
        Text(
            text = kicker.uppercase(),
            style = tokens.type.appBarKicker,
            color = tokens.palette.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        AppBarSlot(right)
    }
}

/**
 * A 28dp app-bar slot action: a stroke icon with the prototype's 6dp
 * padding, ink tint, Button role, and a screen-reader label.
 */
@Composable
fun EditorialAppBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
) {
    val tokens = LocalEditorialTokens.current
    Box(
        modifier =
            modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tokens.palette.ink,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun AppBarSlot(content: (@Composable () -> Unit)?) {
    if (content != null) {
        content()
    } else {
        Box(Modifier.width(28.dp))
    }
}
