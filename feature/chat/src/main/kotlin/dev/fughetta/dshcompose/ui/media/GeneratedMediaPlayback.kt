package dev.fughetta.dshcompose.ui.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.fughetta.dshcompose.feature.chat.R
import kotlinx.coroutines.delay

@Composable
internal fun GeneratedImagePreview(
    request: ImageRequest,
    description: String,
    loader: ImageLoader,
) {
    var scale by remember(request) { mutableFloatStateOf(1f) }
    var pan by remember(request) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, offset, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        pan = if (scale == 1f) Offset.Zero else pan + offset
    }
    Box(
        Modifier.fillMaxSize().clipToBounds().transformable(transform),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            request,
            description,
            loader,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX =
                        pan.x.coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                    translationY =
                        pan.y.coerceIn(
                            -size.height * (scale - 1) / 2,
                            size.height * (scale - 1) / 2,
                        )
                },
        )
    }
}

/**
 * Playback receives only a staged private file. Host URLs and credentials never enter MediaPlayer.
 */
private class VideoPlayback(private val path: String, private val audio: AudioManager) :
    SurfaceHolder.Callback {
    var ready by mutableStateOf(false)
        private set

    var playing by mutableStateOf(false)
        private set

    var duration by mutableIntStateOf(0)
        private set

    var position by mutableIntStateOf(0)
        private set

    var failed by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null
    private var holder: SurfaceHolder? = null
    private var active = false
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && playing) {
                    try {
                        player?.pause()
                    } catch (_: IllegalStateException) {}
                    playing = false
                }
            }
            .build()

    override fun surfaceCreated(holder: SurfaceHolder) {
        this.holder = holder
        prepare()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        this.holder = null
        release()
    }

    fun foreground(value: Boolean) {
        active = value
        if (value) prepare() else release()
    }

    private fun prepare() {
        val surface = holder ?: return
        if (!active || player != null) return
        ready = false
        failed = false
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(attributes)
            next.setDisplay(surface)
            next.setDataSource(path)
            next.setOnPreparedListener {
                if (player !== it || !active) return@setOnPreparedListener
                duration = it.duration.coerceAtLeast(0)
                ready = true
                it.seekTo(position.coerceIn(0, duration))
            }
            next.setOnCompletionListener {
                if (player === it) {
                    playing = false
                    position = duration
                    audio.abandonAudioFocusRequest(focus)
                }
            }
            next.setOnErrorListener { current, _, _ ->
                if (player === current) {
                    failed = true
                    release()
                }
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            failed = true
            release()
        }
    }

    fun toggle() {
        val current = player ?: return
        if (!ready) return
        try {
            if (playing) {
                current.pause()
                audio.abandonAudioFocusRequest(focus)
            } else {
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    return
                if (position >= duration) {
                    current.seekTo(0)
                    position = 0
                }
                current.start()
            }
            playing = !playing
        } catch (_: IllegalStateException) {
            failed = true
            release()
        }
    }

    fun seek(fraction: Float) {
        if (!ready) return
        try {
            position = (fraction.coerceIn(0f, 1f) * duration).toInt()
            player?.seekTo(position)
        } catch (_: IllegalStateException) {
            failed = true
            release()
        }
    }

    fun tick() {
        if (ready)
            try {
                position = player?.currentPosition ?: position
            } catch (_: IllegalStateException) {
                failed = true
                release()
            }
    }

    fun release() {
        val current = player
        player = null
        if (current != null) {
            if (ready)
                try {
                    position = current.currentPosition
                } catch (_: IllegalStateException) {}
            current.setOnPreparedListener(null)
            current.setOnErrorListener(null)
            current.setOnCompletionListener(null)
            current.release()
        }
        ready = false
        playing = false
        audio.abandonAudioFocusRequest(focus)
    }
}

@Composable
internal fun GeneratedVideoPlayer(path: String) {
    val context = LocalContext.current
    val playback =
        remember(path, context) {
            VideoPlayback(path, context.getSystemService(AudioManager::class.java))
        }
    var seeking by remember(path) { mutableStateOf<Float?>(null) }
    LifecycleStartEffect(playback) {
        playback.foreground(true)
        onStopOrDispose { playback.foreground(false) }
    }
    DisposableEffect(playback) { onDispose { playback.release() } }
    LaunchedEffect(playback) {
        while (true) {
            delay(250)
            playback.tick()
        }
    }
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> SurfaceView(context).also { it.holder.addCallback(playback) } },
            onRelease = {
                it.holder.removeCallback(playback)
                playback.release()
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (playback.failed)
            Text(
                stringResource(R.string.media_playback_failed),
                color = MaterialTheme.colorScheme.error,
            )
        if (!playback.ready && !playback.failed) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = playback::toggle, enabled = playback.ready) {
                Text(
                    stringResource(
                        if (playback.playing) R.string.media_pause else R.string.media_play,
                    ),
                )
            }
            val position =
                seeking
                    ?: if (playback.duration == 0) 0f
                    else playback.position.toFloat() / playback.duration
            val slider = rememberSliderState(position)
            slider.value = position
            Slider(
                slider,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let(playback::seek)
                    seeking = null
                },
                enabled = playback.ready,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${playback.position / 1000}s / ${playback.duration / 1000}s",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
