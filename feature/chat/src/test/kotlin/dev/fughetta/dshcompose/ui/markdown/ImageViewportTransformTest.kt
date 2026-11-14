package dev.fughetta.dshcompose.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class ImageViewportTransformTest {
    @Test
    fun pinchKeepsTheTouchedPointFixedAndPanningZoomLimitsAndResizeKeepTheImageInsideTheViewport() {
        val fitted = ImageViewportTransform()
        val pinched = fitted.transformed(400f, 200f, 100f, 50f, 2f)
        assertEquals(2f, pinched.scale, 0f)
        assertEquals(100f, pinched.x, 0f)
        assertEquals(50f, pinched.y, 0f)
        // The touched point remains at the same screen coordinate after scaling about the centre.
        assertEquals(100f, 200f + (100f - 200f) * pinched.scale + pinched.x, 0f)
        assertEquals(50f, 100f + (50f - 100f) * pinched.scale + pinched.y, 0f)
        val panned = pinched.transformed(400f, 200f, 100f, 50f, 1f, 10_000f, -10_000f)
        assertEquals(200f, panned.x, 0f)
        assertEquals(-100f, panned.y, 0f)
        assertEquals(ImageViewportTransform(2f, 32f, -16f), panned.bounded(64f, 32f))
        assertEquals(fitted, panned.transformed(400f, 200f, 100f, 50f, 0.1f))
        assertEquals(
            ImageViewportTransform(2.5f, 150f, 75f),
            fitted.doubleTapped(400f, 200f, 100f, 50f),
        )
        assertEquals(fitted, pinched.doubleTapped(400f, 200f, 100f, 50f))
        val maximum = fitted.transformed(400f, 200f, 0f, 0f, 64f)
        assertEquals(8f, maximum.scale, 0f)
        assertEquals(maximum, maximum.transformed(400f, 200f, 300f, 150f, 2f))
        assertTrue(pinched.zoomed)
        assertFalse(fitted.zoomed)
    }
}
