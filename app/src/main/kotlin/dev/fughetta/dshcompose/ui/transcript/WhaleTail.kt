// Port of the pre-Compose Flutter whale-tail indicator
// (git c085b8c5:app/lib/ui/chat/widgets/whale_tail.dart). The sprite sheet is the
// old assets/whale/whale_2.png: 24 white-on-transparent frames of 120x96 in 6
// columns by 4 rows, 30 ms per frame; the still frame is whale_1.png. Only the
// "normal swim speed" level is ported; the four speed levels and the cross-fade
// between sheets are dropped because the Compose transcript has no TPS data
// source to pick a level from.
package dev.fughetta.dshcompose.ui.transcript

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import kotlin.math.roundToInt

private const val FrameWidth = 120
private const val FrameHeight = 96
private const val FrameColumns = 6
private const val FrameCount = 24
private const val FrameNanos = 30_000_000L // 30 ms per frame.

/**
 * Both sheets decoded once per process (the swimming one is a 720x384 ARGB bitmap, about 1.1 MiB).
 * Indicators appear in the drawer, in tool rows and in the transcript at the same time, so a
 * per-composable decode would multiply that. A failed decode stays null and is retried on the next
 * composition instead of taking the surrounding row down with it.
 */
private object WhaleSheets {
    private var swimming: ImageBitmap? = null
    private var still: ImageBitmap? = null

    @Synchronized
    fun swimming(context: Context): ImageBitmap? =
        swimming ?: decode(context, R.drawable.whale_tail).also { swimming = it }

    @Synchronized
    fun still(context: Context): ImageBitmap? =
        still ?: decode(context, R.drawable.whale_tail_still).also { still = it }

    private fun decode(context: Context, resource: Int): ImageBitmap? =
        BitmapFactory.decodeResource(context.resources, resource)?.asImageBitmap()
}

/**
 * The classic DSH whale-tail "still working" indicator.
 *
 * With [still] it draws the resting frame and runs no clock, which is what the empty state wants.
 *
 * Decorative by default: it draws no text and carries no content description, so the surrounding
 * row keeps its own label and click target. Pass [contentDescription] where the whale *is* the only
 * thing that says something is loading.
 */
@Composable
internal fun WhaleTail(
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    still: Boolean = false,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val sheet =
        remember(context, still) {
            if (still) WhaleSheets.still(context) else WhaleSheets.swimming(context)
        } ?: return
    // The frame index is only read from the draw lambda below, so advancing it invalidates the
    // draw phase of this node without recomposing the strip or relaying it out.
    val frame = remember { mutableIntStateOf(0) }
    LaunchedEffect(sheet, still) {
        if (still) return@LaunchedEffect
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                frame.intValue = (((now - start) / FrameNanos).toInt()) % FrameCount
            }
        }
    }
    val description = contentDescription
    Canvas(
        modifier
            .size(width = height * 5 / 4, height = height)
            .then(
                if (description == null) Modifier
                else Modifier.semantics { this.contentDescription = description },
            ),
    ) {
        val current = if (still) 0 else frame.intValue
        val column = current % FrameColumns
        val row = current / FrameColumns
        val dstWidth = size.width.roundToInt().coerceAtLeast(1)
        val dstHeight = size.height.roundToInt().coerceAtLeast(1)
        // The sheet is a white + alpha mask; SrcIn keeps its coverage and paints it in the tint.
        drawImage(
            image = sheet,
            srcOffset = IntOffset(column * FrameWidth, row * FrameHeight),
            srcSize = IntSize(FrameWidth, FrameHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(dstWidth, dstHeight),
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            filterQuality = FilterQuality.Medium,
        )
    }
}
