package com.github.jayteealao.playster.screens.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.github.jayteealao.playster.screens.LoadingScreen
import com.github.jayteealao.playster.screens.playlist.PlaylistScreen

@Composable
fun PlaysterNavHost(
    navHostController: NavHostController,
    authViewModel: AuthViewModel = hiltViewModel()
) {

    val loggedIn by remember {
        authViewModel.loggedIn
    }

    NavHost(navController = navHostController, startDestination = "loader") {

        composable(route = "onboard") {
            AuthScreen(
                authViewModel,
                onSignIn = { navHostController.navigate("list") }
                )
        }

        composable("list") {
            PlaylistScreen(authViewModel)
        }

        composable("loader") {
            LoadingScreen(
                loggedIn = loggedIn,
                navigator =  { navHostController.navigate(it) }
            )
        }
    }
}