package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.images.ReplyImageSource
import dev.fughetta.dshcompose.domain.images.replyImageSource
import dev.fughetta.dshcompose.domain.links.workspaceLinkPath
import dev.fughetta.dshcompose.ui.ContextMenuPopupAnchor
import dev.fughetta.dshcompose.ui.ImageExportOperation
import dev.fughetta.dshcompose.ui.rememberContextMenuAnchor
import kotlin.math.roundToInt

data class ReplyImageRequest(
    val sessionId: String,
    val address: String,
    val generation: Long,
    val retry: Int = 0,
) {
    override fun toString() = "ReplyImageRequest(generation=$generation)"
}

data class ReplyImageContext(val loader: ImageLoader?, val sessionId: String, val generation: Long)

val LocalReplyImages = staticCompositionLocalOf { ReplyImageContext(null, "", 0) }
val LocalGeneratedMediaPaths = staticCompositionLocalOf<Set<String>> { emptySet() }

val LocalReplyImageExport =
    staticCompositionLocalOf<(ReplyImageRequest, ImageExportOperation) -> Unit> { { _, _ -> } }

internal val LocalReplyGalleryRegistration =
    staticCompositionLocalOf<(Int, Boolean) -> Unit> { { _, _ -> } }

internal val LocalReplyGalleryOpen = staticCompositionLocalOf<(Int) -> Unit> { {} }

@Composable
internal fun ReplyImage(
    address: String,
    alt: String,
    index: Int,
    sizeHint: HtmlImageSize? = null,
    aspect: Float? = null,
) {
    val generated = LocalGeneratedMediaPaths.current
    if (
        address in generated ||
            dev.fughetta.dshcompose.domain.links.workspaceLinkPath(address) in generated
    )
        return
    val images = LocalReplyImages.current
    val openGallery = LocalReplyGalleryOpen.current
    val register = LocalReplyGalleryRegistration.current
    DisposableEffect(index, register) {
        register(index, true)
        onDispose { register(index, false) }
    }
    val description =
        alt.takeUnless { it == "\uFFFC" || it.isBlank() } ?: stringResource(R.string.image)
    var active by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        active = true
        onStopOrDispose { active = false }
    }
    key(images.generation, images.sessionId, address) {
        var retry by remember { mutableIntStateOf(0) }
        var menu by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val anchor = rememberContextMenuAnchor()
        Box(anchor.modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (aspect != null)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val width = maxWidth.value
                        val height = (width / aspect).coerceIn(1f, 480f)
                        var visible by remember(address) { mutableStateOf(false) }
                        Box(
                            Modifier.fillMaxWidth().height(height.dp).onVisibilityChanged(
                                minDurationMs = 0,
                                minFractionVisible = 0.001f,
                            ) {
                                visible = it
                            },
                        ) {
                            if (active && visible)
                                ReplyBitmap(
                                    address,
                                    description,
                                    retry,
                                    HtmlImageSize(width, height),
                                    Modifier.fillMaxSize(),
                                    onClick = { if (!failed) openGallery(index) },
                                    onMenu = {
                                        anchor.capture()
                                        menu = true
                                    },
                                    onFailed = { failed = it },
                                )
                        }
                    }
                else if (active)
                    ReplyBitmap(
                        address,
                        description,
                        retry,
                        sizeHint,
                        Modifier.widthIn(
                                max =
                                    sizeHint?.width?.coerceAtMost(4096f)?.dp
                                        ?: androidx.compose.ui.unit.Dp.Unspecified,
                            )
                            .fillMaxWidth()
                            .heightIn(
                                min = minOf(64f, sizeHint?.height ?: 64f).dp,
                                max = minOf(480f, sizeHint?.height ?: 480f).dp,
                            ),
                        onClick = { if (!failed) openGallery(index) },
                        onMenu = {
                            anchor.capture()
                            menu = true
                        },
                        onFailed = { failed = it },
                    )
                Text(description, style = MaterialTheme.typography.labelMedium)
                if (failed || images.loader == null) {
                    Text(
                        stringResource(R.string.markdown_image_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (images.loader != null)
                    TextButton(
                        onClick = {
                            failed = false
                            retry++
                        },
                    ) {
                        Text(stringResource(R.string.refresh))
                    }
            }
            ContextMenuPopupAnchor(anchor) {
                ReplyImageExportMenu(
                    menu,
                    { menu = false },
                    ReplyImageRequest(images.sessionId, address, images.generation),
                )
            }
        }
    }
}

@Composable
private fun ReplyBitmap(
    address: String,
    description: String,
    retry: Int,
    sizeHint: HtmlImageSize?,
    modifier: Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFailed: (Boolean) -> Unit,
) {
    val loader = LocalReplyImages.current.loader ?: return
    val request = rememberReplyImageRequest(address, 1024, retry, sizeHint)
    AsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onMenu),
        onError = { onFailed(true) },
        onSuccess = { onFailed(false) },
    )
}

@Composable
internal fun rememberReplyImageRequest(
    address: String,
    size: Int,
    retry: Int,
    sizeHint: HtmlImageSize? = null,
): ImageRequest {
    val images = LocalReplyImages.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val width =
        sizeHint?.width?.let { (it * density).coerceIn(1f, size.toFloat()).roundToInt() } ?: size
    val height =
        sizeHint?.height?.let { (it * density).coerceIn(1f, size.toFloat()).roundToInt() } ?: size
    return remember(address, width, height, images.generation, images.sessionId, retry) {
        val source = replyImageSource(address)
        ImageRequest.Builder(context)
            .data(ReplyImageRequest(images.sessionId, address, images.generation, retry))
            .size(width, height)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(
                if (source is ReplyImageSource.Web) CachePolicy.ENABLED else CachePolicy.DISABLED,
            )
            .build()
    }
}
