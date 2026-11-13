package dev.fughetta.dshcompose.ui.markdown

/** Translation is relative to the viewport centre, matching graphicsLayer's pivot. */
internal data class ImageViewportTransform(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
) {
    val zoomed
        get() = scale > 1.01f

    fun bounded(width: Float, height: Float): ImageViewportTransform {
        if (scale == 1f) return copy(x = 0f, y = 0f)
        val horizontal = width * (scale - 1f) / 2f
        val vertical = height * (scale - 1f) / 2f
        return copy(x = x.coerceIn(-horizontal, horizontal), y = y.coerceIn(-vertical, vertical))
    }

    fun transformed(
        width: Float,
        height: Float,
        focusX: Float,
        focusY: Float,
        zoom: Float,
        panX: Float = 0f,
        panY: Float = 0f,
    ): ImageViewportTransform {
        val nextScale = (scale * zoom).coerceIn(1f, 8f)
        val ratio = nextScale / scale
        return ImageViewportTransform(
                nextScale,
                x * ratio + (focusX - width / 2f) * (1f - ratio) + panX,
                y * ratio + (focusY - height / 2f) * (1f - ratio) + panY,
            )
            .bounded(width, height)
    }

    fun doubleTapped(
        width: Float,
        height: Float,
        focusX: Float,
        focusY: Float,
    ): ImageViewportTransform =
        if (zoomed) ImageViewportTransform()
        else transformed(width, height, focusX, focusY, 2.5f / scale)
}
