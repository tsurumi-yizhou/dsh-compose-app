package dev.fughetta.dshcompose.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val pdfRendering = Mutex()

private data class RenderedPdfPage(val bitmap: Bitmap, val count: Int)

@Composable
internal fun WorkspacePdf(
    path: String,
    name: String,
    modifier: Modifier = Modifier,
    pagingEnabled: Boolean = true,
) {
    var page by remember(path) { mutableIntStateOf(0) }
    var rendered by remember(path, page) { mutableStateOf<Result<RenderedPdfPage>?>(null) }
    LaunchedEffect(path, page) {
        rendered = pdfRendering.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                        .use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                val count = renderer.pageCount
                                renderer.openPage(page).use { source ->
                                    val size = pdfPreviewSize(source.width, source.height)
                                    val bitmap =
                                        Bitmap.createBitmap(
                                            size.first,
                                            size.second,
                                            Bitmap.Config.ARGB_8888,
                                        )
                                    try {
                                        bitmap.eraseColor(Color.WHITE)
                                        source.render(
                                            bitmap,
                                            null,
                                            null,
                                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                        )
                                        RenderedPdfPage(bitmap, count)
                                    } catch (error: Throwable) {
                                        bitmap.recycle()
                                        throw error
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
    Column(modifier) {
        val current = rendered?.getOrNull()
        when {
            current != null ->
                Image(
                    current.bitmap.asImageBitmap(),
                    name,
                    Modifier.fillMaxWidth().weight(1f),
                    contentScale = ContentScale.Fit,
                )
            rendered?.isFailure == true ->
                Text(
                    stringResource(R.string.workspace_pdf_unreadable),
                    color = MaterialTheme.colorScheme.error,
                )
            else -> CircularProgressIndicator()
        }
        Row {
            TextButton(
                onClick = { page-- },
                enabled = pagingEnabled && current != null && page > 0,
            ) {
                Text(stringResource(R.string.workspace_file_previous))
            }
            if (current != null)
                Text(stringResource(R.string.workspace_pdf_page_count, page + 1, current.count))
            TextButton(
                onClick = { page++ },
                enabled = pagingEnabled && current != null && page + 1 < current.count,
            ) {
                Text(stringResource(R.string.workspace_file_next))
            }
        }
    }
}

fun pdfPreviewSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(2.0, 2048.0 / maxOf(width, height))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
