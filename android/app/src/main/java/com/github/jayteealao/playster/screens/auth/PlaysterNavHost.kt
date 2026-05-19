package com.github.jayteealao.playster.screens.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.github.jayteealao.playster.screens.LoadingScreen
import com.github.jayteealao.playster.screens.playlist.PlaylistScreen
import com.github.jayteealao.playster.screens.playlist.VideoListScreen
import com.github.jayteealao.playster.screens.videoDetail.VideoDetailScreen

@Composable
fun PlaysterNavHost(
    navHostController: NavHostController,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val loggedIn by authViewModel.loggedIn.collectAsStateWithLifecycle()

    NavHost(navController = navHostController, startDestination = "loader") {
        composable(route = "onboard") {
            AuthScreen(
                authViewModel = authViewModel,
                onSignIn = {
                    navHostController.navigate("list") {
                        popUpTo("onboard") { inclusive = true }
                    }
                },
            )
        }

        composable("list") {
            PlaylistScreen(
                onOpenPlaylist = { playlistId ->
                    navHostController.navigate("videos/$playlistId")
                },
            )
        }

        composable(
            route = "videos/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) {
            VideoListScreen(
                onBack = { navHostController.popBackStack() },
                onOpenVideo = { videoId, autoDispatch ->
                    navHostController.navigate(
                        "videoDetail/$videoId?autoDispatch=$autoDispatch",
                    )
                },
            )
        }

        composable(
            route = "videoDetail/{videoId}?autoDispatch={autoDispatch}",
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("autoDispatch") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) {
            VideoDetailScreen(onBack = { navHostController.popBackStack() })
        }

        composable("loader") {
            LoadingScreen(
                loggedIn = loggedIn,
                navigator = { dest ->
                    navHostController.navigate(dest) {
                        popUpTo("loader") { inclusive = true }
                    }
                },
            )
        }
    }
}
