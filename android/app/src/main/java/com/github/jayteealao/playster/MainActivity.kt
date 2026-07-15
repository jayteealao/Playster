package com.github.jayteealao.playster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.github.jayteealao.playster.navigation.EditorialNavGraph
import com.github.jayteealao.playster.screens.auth.AuthViewModel
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.EditorialThemeGate
import com.github.jayteealao.playster.ui.editorial.chrome.EditorialAppScaffold
import com.github.jayteealao.playster.ui.editorial.chrome.applyEditorialSystemBars
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity behind the editorial reader. The theme gate resolves
 * the saved paper before any frame (window background) and keeps the OS
 * splash on the same paper for future cold starts; system bars go
 * transparent over the paper field; the scaffold + graph carry the whole
 * seven-route IA.
 *
 * Face, size, and line-height render at their defaults here — the settings
 * screen slice wires the live preference flows when it lands.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        EditorialThemeGate.applyPreSetContent(this)
        EditorialThemeGate.syncSplashTheme(this)
        super.onCreate(savedInstanceState)
        val palette = EditorialThemeGate.savedPalette(this)
        applyEditorialSystemBars(this, palette)
        setContent {
            EditorialTheme(palette = palette) {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = hiltViewModel()
                val loggedIn by authViewModel.loggedIn.collectAsStateWithLifecycle()
                EditorialAppScaffold(navController = navController) {
                    EditorialNavGraph(
                        navController = navController,
                        loggedIn = loggedIn,
                    )
                }
            }
        }
    }
}
