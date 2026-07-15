package com.github.jayteealao.playster.screens.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * The playback surface: an [AndroidView] hosting a [YouTubePlayerView], the
 * only ToS-compliant path (the official IFrame embed in a WebView). The view is
 * `remember`ed keyed on the videoId so it survives recomposition — including the
 * panel's collapse/expand height animation — and the WebView is never
 * detached/re-inited across those changes (the continuity the Masthead-band
 * panel contract requires).
 *
 * Automatic initialization is disabled so we can pass [IFramePlayerOptions] with
 * the web UI turned off (`controls(0)` — the editorial controls are ours),
 * related-video and annotation chrome suppressed, and the fullscreen button
 * hidden. `handleNetworkEvents = true` lets the library register its own
 * reconnection receiver; the origin defaults to `https://<packageName>`, and the
 * library serves the player from a bundled HTML asset with hardware
 * acceleration, which together keep the REQUEST_MISSING_HTTP_REFERER (153) path
 * from firing under normal conditions (source:
 * .scratch/sources/ayp/core/.../options/IFramePlayerOptions.kt at tag 13.0.0 —
 * `origin` defaults to `https://${context.packageName}`).
 *
 * The view is registered as a lifecycle observer so it pauses on `ON_STOP` and
 * releases on `ON_DESTROY`; the [DisposableEffect] also releases it when the
 * host leaves composition.
 */
@Composable
fun YouTubePlayerHost(
    controller: PlaybackController,
    videoId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val playerView =
        remember(videoId) {
            YouTubePlayerView(context).apply {
                enableAutomaticInitialization = false
            }
        }

    DisposableEffect(playerView, controller) {
        val options =
            IFramePlayerOptions.Builder(context)
                .controls(0)
                .rel(0)
                .ivLoadPolicy(3)
                .fullscreen(0)
                .build()
        playerView.initialize(controller.listener, handleNetworkEvents = true, playerOptions = options)
        lifecycleOwner.lifecycle.addObserver(playerView)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(playerView)
            playerView.release()
        }
    }

    AndroidView(factory = { playerView }, modifier = modifier)
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
