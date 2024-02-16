package com.github.jayteealao.playster.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun LoadingScreen(loggedIn: Boolean, navigator: (String) -> Unit = {}) {

    LaunchedEffect(loggedIn) {
        Log.d("Loading Screen", "navigation event - $loggedIn")
        delay(2000L)
        if (loggedIn) {
            navigator("list")
        } else {
            navigator("onboard")
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}