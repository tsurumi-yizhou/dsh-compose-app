package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.chat.R
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch

internal data class ReplyGallerySelection(
    val images: PersistentList<MarkdownGalleryImage>,
    val initialIndex: Int,
)

internal fun replyGallerySelection(
    images: PersistentList<MarkdownGalleryImage>,
    visible: Collection<Int>,
    clicked: Int,
): ReplyGallerySelection? {
    if (clicked !in images.indices) return null
    val order =
        visible
            .filter { it in images.indices }
            .sorted()
            .let { if (clicked in it) it else listOf(clicked) }
    return ReplyGallerySelection(
        order.map { images[it] }.toPersistentList(),
        order.indexOf(clicked),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyImageGallery(selection: ReplyGallerySelection, onClose: () -> Unit) {
    val images = LocalReplyImages.current
    val loader = images.loader ?: return
    val animatePages = dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings.current.animations
    val pager = rememberPagerState(initialPage = selection.initialIndex) { selection.images.size }
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var exportMenu by remember { mutableStateOf(false) }
    val retries = remember { mutableStateMapOf<Int, Int>() }
    LifecycleStartEffect(Unit) {
        active = true
        onStopOrDispose { active = false }
    }
    LaunchedEffect(pager.settledPage) { zoomed = false }
    val description =
        selection.images[pager.settledPage].alt.ifBlank {
            stringResource(CoreDesignsystemR.string.image)
        }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(
                    title = { Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        TextButton(onClick = onClose) {
                            Text(stringResource(CoreDesignsystemR.string.close))
                        }
                    },
                    actions = {
                        TextButton(onClick = { exportMenu = true }) {
                            Text(stringResource(R.string.image_actions))
                        }
                        ReplyImageExportMenu(
                            exportMenu,
                            { exportMenu = false },
                            ReplyImageRequest(
                                images.sessionId,
                                selection.images[pager.settledPage].address,
                                images.generation,
                            ),
                        )
                        if (zoomed)
                            TextButton(onClick = { fitRequest++ }) {
                                Text(stringResource(R.string.image_fit))
                            }
                        TextButton(
                            onClick = {
                                retries[pager.settledPage] = (retries[pager.settledPage] ?: 0) + 1
                            },
                        ) {
                            Text(stringResource(CoreDesignsystemR.string.refresh))
                        }
                    },
                )
                HorizontalPager(
                    pager,
                    Modifier.weight(1f).fillMaxWidth(),
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 0,
                    key = { it },
                ) { page ->
                    // Adjacent pages may be composed for scrolling, but only the settled page
                    // fetches bytes.
                    if (active && page == pager.settledPage) {
                        val image = selection.images[page]
                        val request =
                            rememberReplyImageRequest(image.address, 2048, retries[page] ?: 0)
                        key(page, active) {
                            ZoomableReplyImage(
                                request,
                                image.alt.ifBlank {
                                    stringResource(CoreDesignsystemR.string.image)
                                },
                                fitRequest,
                                onZoomed = { zoomed = it },
                                onRetry = { retries[page] = (retries[page] ?: 0) + 1 },
                            )
                        }
                    } else
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(selection.images[page].alt)
                        }
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        enabled = pager.settledPage > 0 && !pager.isScrollInProgress,
                        onClick = {
                            scope.launch {
                                if (animatePages) pager.animateScrollToPage(pager.settledPage - 1)
                                else pager.scrollToPage(pager.settledPage - 1)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.image_previous))
                    }
                    Text(
                        stringResource(
                            R.string.image_position,
                            pager.settledPage + 1,
                            selection.images.size,
                        ),
                    )
                    TextButton(
                        enabled =
                            pager.settledPage < selection.images.lastIndex &&
                                !pager.isScrollInProgress,
                        onClick = {
                            scope.launch {
                                if (animatePages) pager.animateScrollToPage(pager.settledPage + 1)
                                else pager.scrollToPage(pager.settledPage + 1)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.image_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableReplyImage(
    request: ImageRequest,
    description: String,
    fitRequest: Int,
    onZoomed: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val loader = LocalReplyImages.current.loader ?: return
    var transform by remember { mutableStateOf(ImageViewportTransform()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember(request) { mutableStateOf(true) }
    var failed by remember(request) { mutableStateOf(false) }
    LaunchedEffect(fitRequest) {
        transform = ImageViewportTransform()
        onZoomed(false)
    }
    val gestures = rememberTransformableState { focus, zoom, pan, _ ->
        val centre =
            if (focus == Offset.Unspecified) Offset(viewport.width / 2f, viewport.height / 2f)
            else focus
        transform =
            transform.transformed(
                viewport.width.toFloat(),
                viewport.height.toFloat(),
                centre.x,
                centre.y,
                zoom,
                pan.x,
                pan.y,
            )
        onZoomed(transform.zoomed)
    }
    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                viewport = it
                transform = transform.bounded(it.width.toFloat(), it.height.toFloat())
            }
            .pointerInput(viewport) {
                detectTapGestures(
                    onDoubleTap = { point ->
                        transform =
                            transform.doubleTapped(
                                viewport.width.toFloat(),
                                viewport.height.toFloat(),
                                point.x,
                                point.y,
                            )
                        onZoomed(transform.zoomed)
                    },
                )
            }
            .transformable(gestures, canPan = { transform.zoomed }),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            imageLoader = loader,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.x
                    translationY = transform.y
                },
            onLoading = {
                loading = true
                failed = false
            },
            onSuccess = {
                loading = false
                failed = false
            },
            onError = {
                loading = false
                failed = true
            },
        )
        if (loading) CircularProgressIndicator()
        if (failed)
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.markdown_image_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(CoreDesignsystemR.string.refresh))
                    }
                }
            }
    }
}
