package com.github.jayteealao.playster.navigation

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.github.jayteealao.playster.screens.auth.AuthScreen
import com.github.jayteealao.playster.screens.home.HomeScreen
import com.github.jayteealao.playster.screens.player.PlayerScreen
import com.github.jayteealao.playster.screens.playlist.PlaylistScreen
import com.github.jayteealao.playster.screens.search.SearchScreen
import com.github.jayteealao.playster.screens.settings.SettingsScreen
import com.github.jayteealao.playster.screens.transcript.TranscriptScreen

/** The mock's `edFade`: 250ms ease-out, entering screen only. */
private const val ED_FADE_DURATION_MS = 250

/** The mock's `translateY(4px) → 0` rise on the entering screen. */
private val ED_FADE_RISE = 4.dp

/**
 * Inert sample id for the temporary skeleton journey (playlist → player →
 * transcript). Skeletons fetch nothing; the id disappears with the screen
 * slice that replaces its skeleton's navigation action.
 */
internal const val SAMPLE_VIDEO_ID = "sample-episode"

// Single-sourced navigation lambdas — every defaulted `*Content` slot below
// calls these instead of re-writing its own `navController.navigate(...)`,
// so a route-shape change or a `launchSingleTop` policy change is one edit,
// not a hunt across seven slot defaults (MNT-1). Kept as private extension
// functions (not a remembered holder) because default parameter expressions
// are evaluated at the call site and can only see the function's own
// parameters — not composable-scoped locals.

private fun NavHostController.openPlaylist(playlistId: String) {
    navigate(EditorialRoutes.playlist(playlistId))
}

/**
 * Navigate to a player route. Always `launchSingleTop`: Home, Playlist,
 * Transcript, and Search can all open the same episode repeatedly, and
 * repeated opens of the same route must not stack duplicate player
 * destinations on the back stack.
 */
private fun NavHostController.openPlayer(videoId: String) {
    navigate(EditorialRoutes.player(videoId)) { launchSingleTop = true }
}

/** Player → Transcript: falls back to the skeleton's sample id when unset. */
private fun NavHostController.openTranscriptFromPlayer(videoId: String) {
    navigate(EditorialRoutes.transcript(videoId.ifEmpty { SAMPLE_VIDEO_ID }))
}

/** Search's jump-to-timestamp deep link into Transcript. */
private fun NavHostController.openTranscriptAt(
    videoId: String,
    startSeconds: Double?,
) {
    navigate(EditorialRoutes.transcript(videoId, startSeconds))
}

private fun NavHostController.openSearch() {
    navigate(EditorialRoutes.SEARCH)
}

private fun NavHostController.openSettings() {
    navigate(EditorialRoutes.SETTINGS)
}

private fun NavHostController.goBack() {
    popBackStack()
}

/**
 * The editorial navigation graph: all seven routes resolve to editorial
 * skeletons until their screen slices land, with the mock's `edFade`
 * entrance (fade + 4dp rise, 250ms ease-out; the leaving screen swaps
 * instantly, exactly like the prototype's remount-only animation).
 *
 * The session gate lives at graph level: a signed-out session is always
 * redirected to [EditorialRoutes.AUTH] over a cleared stack, and a
 * sign-in that happens while on auth lands on home. `loggedIn` comes from
 * the existing auth-state source ([com.github.jayteealao.playster.screens.auth.AuthViewModel.loggedIn]);
 * passing the value (not the ViewModel) keeps the graph testable on the JVM.
 *
 * Reduced motion needs no code here: Compose observes the system animator
 * duration scale (MotionDurationScaleImpl in the installed ui-android
 * artifact), so scale 0 snaps these transitions platform-wide.
 */
@Composable
fun EditorialNavGraph(
    navController: NavHostController,
    loggedIn: Boolean,
    modifier: Modifier = Modifier,
    authContent: @Composable () -> Unit = { AuthScreen() },
    homeContent: @Composable () -> Unit = {
        HomeScreen(
            onOpenPlaylist = { playlistId -> navController.openPlaylist(playlistId) },
            onOpenPlayer = { videoId -> navController.openPlayer(videoId) },
            onOpenSearch = { navController.openSearch() },
            onOpenSettings = { navController.openSettings() },
        )
    },
    playlistContent: @Composable () -> Unit = {
        PlaylistScreen(
            onOpenPlayer = { videoId -> navController.openPlayer(videoId) },
            onBack = { navController.goBack() },
        )
    },
    playerContent: @Composable () -> Unit = {
        PlayerScreen(
            onBack = { navController.goBack() },
            onOpenTranscript = { videoId -> navController.openTranscriptFromPlayer(videoId) },
        )
    },
    transcriptContent: @Composable (String) -> Unit = { videoId ->
        TranscriptScreen(
            onBack = { navController.goBack() },
            onOpenPlayer = { navController.openPlayer(videoId) },
        )
    },
    searchContent: @Composable () -> Unit = {
        SearchScreen(
            onOpenTranscriptAt = { videoId, startSeconds ->
                navController.openTranscriptAt(videoId, startSeconds)
            },
            onOpenPlayer = { videoId -> navController.openPlayer(videoId) },
            onOpenPlaylist = { playlistId -> navController.openPlaylist(playlistId) },
        )
    },
    // Sign-out needs no nav callback here: the session gate above redirects to
    // Auth on the `loggedIn` flip. The Hilt-free default keeps the graph testable.
    settingsContent: @Composable () -> Unit = { SettingsScreen() },
) {
    val riseOffsetPx = with(LocalDensity.current) { ED_FADE_RISE.roundToPx() }
    val edFadeEnter =
        remember(riseOffsetPx) {
            fadeIn(tween(ED_FADE_DURATION_MS, easing = EaseOut)) +
                slideInVertically(tween(ED_FADE_DURATION_MS, easing = EaseOut)) { riseOffsetPx }
        }

    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            navController.navigate(EditorialRoutes.AUTH) {
                popUpTo(0)
                launchSingleTop = true
            }
        } else if (navController.currentDestination?.route == EditorialRoutes.AUTH) {
            navController.navigate(EditorialRoutes.HOME) {
                popUpTo(0)
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = EditorialRoutes.HOME,
        modifier = modifier,
        enterTransition = { edFadeEnter },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { edFadeEnter },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(EditorialRoutes.AUTH) {
            authContent()
        }
        composable(EditorialRoutes.HOME) {
            homeContent()
        }
        composable(
            route = EditorialRoutes.PLAYLIST,
            arguments =
                listOf(
                    navArgument(EditorialRoutes.ARG_PLAYLIST_ID) { type = NavType.StringType },
                ),
        ) {
            playlistContent()
        }
        composable(
            route = EditorialRoutes.PLAYER,
            arguments =
                listOf(
                    navArgument(EditorialRoutes.ARG_VIDEO_ID) { type = NavType.StringType },
                    navArgument(EditorialRoutes.ARG_PLAYLIST_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) {
            playerContent()
        }
        composable(
            route = EditorialRoutes.TRANSCRIPT,
            arguments =
                listOf(
                    navArgument(EditorialRoutes.ARG_VIDEO_ID) { type = NavType.StringType },
                    navArgument(EditorialRoutes.ARG_START_SECONDS) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry ->
            transcriptContent(entry.arguments?.getString(EditorialRoutes.ARG_VIDEO_ID).orEmpty())
        }
        composable(EditorialRoutes.SEARCH) {
            searchContent()
        }
        composable(EditorialRoutes.SETTINGS) {
            settingsContent()
        }
    }
}

/**
 * Bottom-nav root navigation with state preservation — the canonical
 * navigation-compose pattern for "roots don't stack": pop up to the graph's
 * start destination saving section state, avoid duplicate copies of the
 * root, and restore the section's saved state on return.
 */
fun NavHostController.navigateToEditorialRoot(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
