package dev.fughetta.dshcompose.ui.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@Composable
fun ImagePreviewRoute(source: StateFlow<ImagePreviewState?>, onClose: () -> Unit) {
    val state by source.collectAsStateWithLifecycle()
    state?.let { ImagePreviewDialog(it, onClose) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImagePreviewDialog(state: ImagePreviewState, onClose: () -> Unit) {
    val decoded by
        key(state.bytes) {
            produceState<Result<Bitmap>?>(null) {
                val bytes = state.bytes ?: return@produceState
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            decodeSampledPreview { options ->
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            }
                        }
                    }
            }
        }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    state.name ?: stringResource(CoreDesignsystemR.string.image),
                    style = MaterialTheme.typography.titleLarge,
                )
                when {
                    state.error != null ->
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    decoded?.isFailure == true ->
                        Text(stringResource(CoreDesignsystemR.string.attachment_unreadable))
                    decoded?.isSuccess == true ->
                        Image(
                            decoded!!.getOrThrow().asImageBitmap(),
                            contentDescription =
                                state.name ?: stringResource(CoreDesignsystemR.string.image),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                            contentScale = ContentScale.Fit,
                        )
                    else -> LoadingIndicator()
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CoreDesignsystemR.string.close))
                }
            }
        }
    }
}

private fun decodeSampledPreview(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap {
    val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(size)
    var sample = 1
    while (maxOf(size.outWidth, size.outHeight) / sample > 2048) sample *= 2
    return decode(BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Image could not be decoded")
}

@Composable
internal fun WorkspaceImage(path: String, name: String, modifier: Modifier = Modifier) {
    val decoded by
        produceState<Result<Bitmap>?>(null, path) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        decodeSampledPreview { options -> BitmapFactory.decodeFile(path, options) }
                    }
                }
        }
    when {
        decoded?.isSuccess == true ->
            Image(
                decoded!!.getOrThrow().asImageBitmap(),
                contentDescription = name,
                modifier = modifier,
                contentScale = ContentScale.Fit,
            )
        decoded?.isFailure == true ->
            Text(
                stringResource(CoreDesignsystemR.string.attachment_unreadable),
                color = MaterialTheme.colorScheme.error,
            )
        else -> CircularProgressIndicator()
    }
}

/**
 * Decodes in-memory image bytes off the main thread; draws nothing until decoded or when
 * unreadable. [onSettled] runs once per [bytes] after decoding succeeds or fails.
 */
@Composable
fun BytesImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit = {},
) {
    val settled by rememberUpdatedState(onSettled)
    val decoded by
        produceState<Bitmap?>(null, bytes) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        decodeSampledPreview { options ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        }
                    }
                        .getOrNull()
                }
            settled()
        }
    decoded?.let {
        Image(it.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Fit)
    }
}
