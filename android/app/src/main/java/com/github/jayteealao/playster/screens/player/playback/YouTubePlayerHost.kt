package com.github.jayteealao.playster.screens.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService

/**
 * The playback surface: an [AndroidView] rendering the shared [PlaybackSession]'s
 * single retained [com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView]
 * — the only ToS-compliant path (the official IFrame embed in a WebView). The
 * view is created and initialized once by the session and *re-parented* here
 * across recomposition and across routes, so the WebView is never detached or
 * re-inited on a mere route change (the continuity the Masthead-band panel and
 * the Transcript mini-embed both require, and the mechanism that lets one embed
 * survive Player→Transcript navigation).
 *
 * The [DisposableEffect] tells the session a playback surface is on screen
 * ([PlaybackSession.attach] / [PlaybackSession.detach]); the session pauses the
 * embed when the last surface leaves and none re-attaches, so nothing plays off
 * a visible surface (C5). Initialization options (`controls(0)`, no related
 * videos, no fullscreen) and the lifecycle/network wiring now live on the
 * session.
 */
@Composable
fun YouTubePlayerHost(
    session: PlaybackSession,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    DisposableEffect(session) {
        session.attach()
        onDispose { session.detach() }
    }
    AndroidView(factory = { session.view(context) }, modifier = modifier)
}

/**
 * Whether the device currently has no validated internet-capable network — the
 * offline signal [PlaybackController] folds into [PlaybackError.Offline] (AC4's
 * airplane-mode launch). Best-effort and null-safe; treats an unknown state as
 * online so a transient capability read never fabricates an offline error.
 */
@Suppress("ReturnCount") // Null-safe capability reads each degrade to a definite offline/online answer.
fun isDeviceOffline(context: Context): Boolean {
    val cm = context.getSystemService<ConnectivityManager>() ?: return false
    val network = cm.activeNetwork ?: return true
    val caps = cm.getNetworkCapabilities(network) ?: return true
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
