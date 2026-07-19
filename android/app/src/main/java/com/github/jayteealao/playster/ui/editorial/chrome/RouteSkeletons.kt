package com.github.jayteealao.playster.ui.editorial.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice

/*
 * One editorial skeleton per not-yet-built route: the route's app-bar kicker
 * over the loading primitive's quiet italic sentence, tagged
 * `route-skeleton-<route>` for the navigation flows. Each screen slice replaces
 * its skeleton as it lands — the player skeleton retired when the real Player
 * screen took over its route.
 */

/** Search placeholder. */
@Composable
fun SearchRouteSkeleton(modifier: Modifier = Modifier) {
    RouteSkeleton(
        routeTag = "search",
        kicker = "Find Anywhere",
        waitingLine = "The index is being compiled.",
        modifier = modifier,
    )
}

/** Settings placeholder. */
@Composable
fun SettingsRouteSkeleton(modifier: Modifier = Modifier) {
    RouteSkeleton(
        routeTag = "settings",
        kicker = "You · Reader Preferences",
        waitingLine = "Your reader preferences are being fetched.",
        modifier = modifier,
    )
}

@Composable
private fun RouteSkeleton(
    routeTag: String,
    kicker: String,
    waitingLine: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("route-skeleton-$routeTag"),
    ) {
        EditorialAppBar(kicker = kicker)
        Spacer(Modifier.weight(1f))
        EditorialLoadingNotice(
            label = waitingLine,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Spacer(Modifier.weight(1f))
    }
}
