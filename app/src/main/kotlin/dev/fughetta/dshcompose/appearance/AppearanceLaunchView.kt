package dev.fughetta.dshcompose.appearance

import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.github.penfeizhou.animation.webp.WebPDrawable
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.icons.LauncherIcons
import dev.fughetta.dshcompose.ui.appearance.*
import dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings
import java.io.File
import kotlinx.coroutines.*

@Composable
fun AppearanceLaunchPreview(state: AppearanceState, onAction: (AppearanceAction) -> Unit) {
    if (state.previewLaunch)
        Dialog(
            { onAction(AppearanceAction.CloseLaunchPreview) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize()) {
                AppearanceLaunchOverlay(state, false, {}, Modifier.fillMaxSize())
                TextButton(
                    { onAction(AppearanceAction.CloseLaunchPreview) },
                    Modifier.align(Alignment.BottomCenter),
                ) {
                    Text(stringResource(R.string.app_done))
                }
            }
        }
}

/** Mount once at process launch; custom animation/video completes once, silently. */
@Composable
fun AppearanceLaunchOverlay(
    state: AppearanceState,
    ready: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val launch = state.preferences.launch?.takeIf { it.enabled }
    val animate =
        LocalPerformanceSettings.current.animations &&
            android.animation.ValueAnimator.areAnimatorsEnabled()
    val finish by rememberUpdatedState(onFinished)
    var completed by remember(launch?.path) { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    val opacity = remember { Animatable(1f) }
    val reveal = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) {
            delay(200)
            reveal.animateTo(1f, tween(440))
        }
        settled = true
    }
    LaunchedEffect(state.loading, ready, launch?.path, completed, animate, settled) {
        if (
            !state.loading &&
                ready &&
                (!animate || launch == null || launch.kind == LaunchMediaKind.Image || completed)
        ) {
            if (animate) opacity.animateTo(0f, tween(300))
            finish()
        }
    }
    val background =
        when (launch?.backdrop) {
            LaunchMediaBackdrop.Black -> Color.Black
            LaunchMediaBackdrop.White -> Color.White
            LaunchMediaBackdrop.Edge ->
                launch.edge?.let(::Color) ?: MaterialTheme.colorScheme.background
            else -> MaterialTheme.colorScheme.background
        }
    Box(
        modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .alpha(opacity.value)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (launch == null) DefaultLaunchArt(state, animate, reveal.value)
        else LaunchMedia(launch, animate, { completed = true }, Modifier.fillMaxSize())
    }
}

@Composable
private fun LaunchMedia(
    spec: AppearanceLaunch,
    animate: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier,
) {
    val end by rememberUpdatedState(onFinished)
    val mediaContext = LocalContext.current
    if (spec.kind == LaunchMediaKind.Video && animate) {
        BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            val ratio = spec.width.toFloat() / spec.height.coerceAtLeast(1)
            val viewport = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val width =
                if ((spec.fit == LaunchMediaFit.Fill) == (ratio > viewport)) maxHeight * ratio
                else maxWidth
            val height = width / ratio
            AndroidView(
                factory = { context ->
                    LaunchVideoView(context).apply {
                        setVideoPath(spec.path)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            player.setVolume(0f, 0f)
                            start()
                        }
                        setOnCompletionListener { end() }
                        setOnErrorListener { _, _, _ ->
                            end()
                            true
                        }
                    }
                },
                modifier = Modifier.requiredSize(width, height),
                onRelease = { it.stopPlayback() },
            )
        }
    } else if (spec.kind == LaunchMediaKind.Animation && animate && Build.VERSION.SDK_INT < 28) {
        LegacyAnimation(spec, { end() }, modifier)
    } else {
        val drawable by
            produceState<Drawable?>(null, spec.path, animate) {
                value =
                    withContext(Dispatchers.IO) {
                        try {
                            val file =
                                File(
                                    if (spec.kind == LaunchMediaKind.Video) spec.poster ?: spec.path
                                    else spec.path,
                                )
                            if (Build.VERSION.SDK_INT >= 28)
                                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) {
                                    decoder,
                                    info,
                                    _ ->
                                    val ratio =
                                        minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
                                    decoder.setTargetSize(
                                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                                        (info.size.height * ratio).toInt().coerceAtLeast(1),
                                    )
                                }
                            else
                                android.graphics.drawable.BitmapDrawable(
                                    mediaContext.resources,
                                    AppearanceMedia(mediaContext).read(file.path),
                                )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                if (value == null) end()
            }
        AndroidView(
            factory = { context -> ImageView(context) },
            update = { view ->
                view.scaleType =
                    if (spec.fit == LaunchMediaFit.Fill) ImageView.ScaleType.CENTER_CROP
                    else ImageView.ScaleType.FIT_CENTER
                if (view.drawable !== drawable) {
                    view.setImageDrawable(drawable)
                    val image = drawable
                    if (Build.VERSION.SDK_INT >= 28 && image is AnimatedImageDrawable && animate) {
                        image.repeatCount = 0
                        image.registerAnimationCallback(
                            object : android.graphics.drawable.Animatable2.AnimationCallback() {
                                override fun onAnimationEnd(drawable: Drawable) {
                                    end()
                                }
                            },
                        )
                        image.start()
                    } else if (image != null) end()
                }
            },
            modifier = modifier,
            onRelease = { view ->
                if (Build.VERSION.SDK_INT >= 28)
                    (view.drawable as? AnimatedImageDrawable)?.let {
                        it.stop()
                        it.clearAnimationCallbacks()
                    }
                view.setImageDrawable(null)
            },
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun LegacyAnimation(spec: AppearanceLaunch, onFinished: () -> Unit, modifier: Modifier) {
    val end by rememberUpdatedState(onFinished)
    var movie by remember(spec.path) { mutableStateOf<Movie?>(null) }
    var webp by remember(spec.path) { mutableStateOf(false) }
    var prepared by remember(spec.path) { mutableStateOf(false) }
    LaunchedEffect(spec.path) {
        val result =
            withContext(Dispatchers.IO) {
                val header =
                    File(spec.path).inputStream().use { stream ->
                        val bytes = ByteArray(12)
                        stream.read(bytes)
                        bytes.toString(Charsets.ISO_8859_1)
                    }
                val isWebp = header.startsWith("RIFF") && header.endsWith("WEBP")
                isWebp to if (isWebp) null else Movie.decodeFile(spec.path)
            }
        webp = result.first
        movie = result.second
        prepared = true
    }
    if (!prepared) return
    if (webp)
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType =
                        if (spec.fit == LaunchMediaFit.Fill) ImageView.ScaleType.CENTER_CROP
                        else ImageView.ScaleType.FIT_CENTER
                    val drawable = WebPDrawable.fromFile(spec.path)
                    drawable.setLoopLimit(1)
                    drawable.registerAnimationCallback(
                        object : Animatable2Compat.AnimationCallback() {
                            override fun onAnimationEnd(drawable: Drawable) {
                                end()
                            }
                        },
                    )
                    setImageDrawable(drawable)
                    drawable.start()
                }
            },
            modifier = modifier,
            onRelease = { view ->
                (view.drawable as? WebPDrawable)?.let {
                    it.stop()
                    it.clearAnimationCallbacks()
                }
                view.setImageDrawable(null)
            },
        )
    else if (movie != null)
        AndroidView(
            factory = { context ->
                object : View(context) {
                    val started = SystemClock.uptimeMillis()
                    var done = false

                    override fun onDraw(canvas: android.graphics.Canvas) {
                        val animation = movie ?: return
                        val duration = animation.duration().coerceAtLeast(1)
                        val time =
                            (SystemClock.uptimeMillis() - started)
                                .coerceAtMost(duration.toLong())
                                .toInt()
                        animation.setTime(time.coerceAtMost(duration - 1))
                        val sx = width.toFloat() / animation.width()
                        val sy = height.toFloat() / animation.height()
                        val scale =
                            if (spec.fit == LaunchMediaFit.Fill) maxOf(sx, sy) else minOf(sx, sy)
                        canvas.save()
                        canvas.translate(
                            (width - animation.width() * scale) / 2,
                            (height - animation.height() * scale) / 2,
                        )
                        canvas.scale(scale, scale)
                        animation.draw(canvas, 0f, 0f)
                        canvas.restore()
                        if (time < duration) postInvalidateOnAnimation()
                        else if (!done) {
                            done = true
                            end()
                        }
                    }
                }
            },
            modifier = modifier,
        )
    else LaunchedEffect(spec.path) { end() }
}

@Composable
private fun DefaultLaunchArt(state: AppearanceState, animate: Boolean, reveal: Float) {
    val context = LocalContext.current
    val settle = remember { Animatable(if (animate) 0f else 1f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (animate) {
            launch { settle.animateTo(1f, tween(640)) }
            delay(560)
            sweep.animateTo(1f, tween(800))
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(160.dp).scale(1f - .2f * settle.value).clipToBounds()) {
            val resource =
                remember(state.currentIcon) {
                    context.resources.getIdentifier(
                        LauncherIcons.mipmapOf(state.currentIcon),
                        "mipmap",
                        context.packageName,
                    )
                }
            AndroidView(
                factory = { ctx -> ImageView(ctx) },
                update = { it.setImageResource(resource) },
                modifier = Modifier.fillMaxSize(),
            )
            if (sweep.value > 0f && sweep.value < 1f)
                Canvas(Modifier.matchParentSize()) {
                    val x = (sweep.value * 1.8f - .4f) * size.width
                    drawRect(
                        Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = .35f),
                                Color.Transparent,
                            ),
                            Offset(x - size.width * .12f, 0f),
                            Offset(x + size.width * .12f, size.height),
                        ),
                    )
                }
        }
        Text("DSH Compose", Modifier.alpha(reveal), style = MaterialTheme.typography.headlineMedium)
    }
}

private class LaunchVideoView(context: android.content.Context) : VideoView(context) {
    private var resumeAfterHide = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            if (resumeAfterHide) {
                resumeAfterHide = false
                start()
            }
        } else {
            resumeAfterHide = isPlaying
            pause()
        }
    }
}
