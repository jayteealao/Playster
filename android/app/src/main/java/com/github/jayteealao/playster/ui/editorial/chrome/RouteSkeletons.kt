package com.github.jayteealao.playster.ui.editorial.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.jayteealao.playster.ui.editorial.components.EditorialAppBar
import com.github.jayteealao.playster.ui.editorial.components.EditorialIcons
import com.github.jayteealao.playster.ui.editorial.components.EditorialLoadingNotice
import com.github.jayteealao.playster.ui.editorial.components.EditorialTextAction

/*
 * One editorial skeleton per route: the route's app-bar kicker over the
 * loading primitive's quiet italic sentence, tagged `route-skeleton-<route>`
 * for the navigation flows. Each screen slice replaces its skeleton —
 * including the temporary text-action affordances that make the reading
 * journey (home → playlist → player → transcript) drivable before any
 * screen exists. The parameterized skeletons surface their route argument
 * in the kicker so an argument's round-trip is visible on a recording.
 */

/** Player placeholder. */
@Composable
fun PlayerRouteSkeleton(
    videoId: String,
    onFollowTranscript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RouteSkeleton(
        routeTag = "player",
        kicker = "Now Playing · $videoId",
        waitingLine = "The article and its recording are being laid out.",
        modifier = modifier,
    ) {
        SkeletonNavAction(
            routeTag = "player",
            label = "Follow along in the transcript",
            onClick = onFollowTranscript,
        )
    }
}

/** Transcript placeholder. */
@Composable
fun TranscriptRouteSkeleton(
    videoId: String,
    modifier: Modifier = Modifier,
) {
    RouteSkeleton(
        routeTag = "transcript",
        kicker = "Transcript · $videoId",
        waitingLine = "The transcript is being set, line by line.",
        modifier = modifier,
    )
}

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
    action: (@Composable () -> Unit)? = null,
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
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                action()
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SkeletonNavAction(
    routeTag: String,
    label: String,
    onClick: () -> Unit,
) {
    EditorialTextAction(
        text = label,
        onClick = onClick,
        icon = EditorialIcons.Next,
        modifier = Modifier.testTag("skeleton-action-$routeTag"),
    )
}
