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
import com.github.jayteealao.playster.screens.playlist.PlaylistScreen
import com.github.jayteealao.playster.ui.editorial.chrome.PlayerRouteSkeleton
import com.github.jayteealao.playster.ui.editorial.chrome.SearchRouteSkeleton
import com.github.jayteealao.playster.ui.editorial.chrome.SettingsRouteSkeleton
import com.github.jayteealao.playster.ui.editorial.chrome.TranscriptRouteSkeleton

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
            onOpenPlaylist = { playlistId ->
                navController.navigate(EditorialRoutes.playlist(playlistId))
            },
            onOpenPlayer = { videoId ->
                navController.navigate(EditorialRoutes.player(videoId))
            },
            onOpenSearch = { navController.navigate(EditorialRoutes.SEARCH) },
            onOpenSettings = { navController.navigate(EditorialRoutes.SETTINGS) },
        )
    },
    playlistContent: @Composable () -> Unit = {
        PlaylistScreen(
            onOpenPlayer = { videoId ->
                navController.navigate(EditorialRoutes.player(videoId))
            },
            onBack = { navController.popBackStack() },
        )
    },
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
                ),
        ) { entry ->
            val videoId = entry.arguments?.getString(EditorialRoutes.ARG_VIDEO_ID).orEmpty()
            PlayerRouteSkeleton(
                videoId = videoId,
                onFollowTranscript = {
                    // Player → transcript sibling navigation over the id the
                    // player itself was opened with.
                    navController.navigate(
                        EditorialRoutes.transcript(videoId.ifEmpty { SAMPLE_VIDEO_ID }),
                    )
                },
            )
        }
        composable(
            route = EditorialRoutes.TRANSCRIPT,
            arguments =
                listOf(
                    navArgument(EditorialRoutes.ARG_VIDEO_ID) { type = NavType.StringType },
                ),
        ) { entry ->
            TranscriptRouteSkeleton(
                videoId = entry.arguments?.getString(EditorialRoutes.ARG_VIDEO_ID).orEmpty(),
            )
        }
        composable(EditorialRoutes.SEARCH) {
            SearchRouteSkeleton()
        }
        composable(EditorialRoutes.SETTINGS) {
            SettingsRouteSkeleton()
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
