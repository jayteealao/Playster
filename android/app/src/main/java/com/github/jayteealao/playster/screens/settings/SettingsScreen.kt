package com.github.jayteealao.playster.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings — the journey terminus where the token layer gets its public controls.
 * Pure composition over [SettingsContent]; the ViewModel owns the live-preference
 * writes (which re-theme the running app), the progress-derived stats, and
 * sign-out. There is no sign-out nav callback: the graph's session gate redirects
 * to Auth on the `loggedIn` flip.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onSelectAxis = viewModel::onSelectAxis,
        onSetDefaultSpeed = viewModel::onSetDefaultSpeed,
        onSignOut = viewModel::onSignOut,
        modifier = modifier,
    )
}
