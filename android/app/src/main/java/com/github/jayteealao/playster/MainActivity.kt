package com.github.jayteealao.playster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.github.jayteealao.playster.navigation.EditorialNavGraph
import com.github.jayteealao.playster.screens.auth.AuthViewModel
import com.github.jayteealao.playster.screens.player.playback.PlaybackSession
import com.github.jayteealao.playster.ui.editorial.EditorialTheme
import com.github.jayteealao.playster.ui.editorial.EditorialThemeGate
import com.github.jayteealao.playster.ui.editorial.ReadingPreferencesStore
import com.github.jayteealao.playster.ui.editorial.chrome.EditorialAppScaffold
import com.github.jayteealao.playster.ui.editorial.chrome.applyEditorialSystemBars
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single activity behind the editorial reader. The theme gate resolves
 * the saved paper before any frame (window background) and keeps the OS
 * splash on the same paper for future cold starts; system bars go
 * transparent over the paper field; the scaffold + graph carry the whole
 * seven-route IA.
 *
 * The live reading preferences ([ReadingPreferencesStore]) are collected into
 * [EditorialTheme] here, so picking a paper/face/size/line-height in Settings
 * re-themes the whole running tree without a restart. A palette change also
 * re-applies the system bars, so Night flips to dark chrome live.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * The activity-shared embed, injected here only so it is torn down when the
     * activity truly finishes (not on config-change recreation, when the
     * `@ActivityRetainedScoped` session must survive to keep playback going).
     */
    @Inject
    lateinit var playbackSession: PlaybackSession

    /** The live reading-preferences bridge — its flows drive [EditorialTheme]. */
    @Inject
    lateinit var readingPreferences: ReadingPreferencesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        EditorialThemeGate.applyPreSetContent(this)
        EditorialThemeGate.syncSplashTheme(this)
        super.onCreate(savedInstanceState)
        applyEditorialSystemBars(this, EditorialThemeGate.savedPalette(this))
        setContent {
            val palette by readingPreferences.palette.collectAsStateWithLifecycle()
            val face by readingPreferences.face.collectAsStateWithLifecycle()
            val sizeStep by readingPreferences.sizeStep.collectAsStateWithLifecycle()
            val lineHeightStep by readingPreferences.lineHeightStep.collectAsStateWithLifecycle()

            // Re-apply the transparent edge-to-edge bars whenever the paper
            // changes so a live Night switch flips the system-bar icons dark,
            // and re-sync the persisted OS splash theme at change-time so the
            // very NEXT cold start already splashes in the new paper (the
            // onCreate sync alone lags one launch behind a Settings change).
            LaunchedEffect(palette) {
                applyEditorialSystemBars(this@MainActivity, palette)
                EditorialThemeGate.syncSplashTheme(this@MainActivity)
            }

            EditorialTheme(
                palette = palette,
                face = face,
                sizeStep = sizeStep,
                lineHeightStep = lineHeightStep,
            ) {
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

    override fun onDestroy() {
        // Release the retained embed only on a real finish; a config-change
        // recreation keeps the session (and its playback) alive.
        if (isFinishing) playbackSession.release()
        super.onDestroy()
    }
}
