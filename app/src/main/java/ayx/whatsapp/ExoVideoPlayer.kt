package ayx.whatsapp

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Reusable ExoPlayer (Media3) video player for Compose.
 *
 * Replaces the laggy VideoView/android.media.MediaPlayer-based playback in:
 * - chat full-screen video viewer
 * - composer inline video preview
 * - status editor video preview (trim window, mute, replay)
 * - status viewer video playback
 *
 * The player instance is exposed via [onPlayer] so call sites can drive
 * play/pause/seek/volume/position exactly like they did with VideoView.
 * The player is released automatically when the composable leaves the composition.
 */
@Composable
fun ExoVideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    autoPlay: Boolean = true,
    looping: Boolean = false,
    muted: Boolean = false,
    showController: Boolean = false,
    onPlayer: (ExoPlayer) -> Unit = {},
    onReady: () -> Unit = {},
    onCompletion: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (!headers.isNullOrEmpty()) setDefaultRequestProperties(headers)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                volume = if (muted) 0f else 1f
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) onReady()
                        if (state == Player.STATE_ENDED) onCompletion()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        onError(error.message ?: "playback error")
                    }
                })
                onPlayer(this)
            }
    }
    // (Re)load whenever the URI changes.
    LaunchedEffect(uri) {
        runCatching {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            if (autoPlay) player.play()
        }
    }
    // Keep mute + looping in sync if the call site toggles them.
    LaunchedEffect(muted) { runCatching { player.volume = if (muted) 0f else 1f } }
    LaunchedEffect(looping) {
        runCatching { player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    // S1: pause when the app goes to background (home button / switch app).
    // Without this, the audio keeps playing because the composition is NOT
    // disposed when the Activity is merely paused.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching { player.pause() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                useController = showController
            }
        },
        modifier = modifier,
    )
}
