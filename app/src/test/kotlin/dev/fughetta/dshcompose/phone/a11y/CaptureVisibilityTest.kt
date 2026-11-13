package dev.fughetta.dshcompose.phone.a11y

import org.junit.Assert.*
import org.junit.Test

class CaptureVisibilityTest {
    @Test
    fun overlappingHoldsCannotRestoreEachOtherEarly() {
        val visibility = CaptureVisibility()
        assertFalse(visibility.hidden)
        assertTrue(visibility.update(true)) // agent hides
        assertFalse(visibility.update(true)) // a second caller also hides
        assertTrue(visibility.hidden)
        assertFalse(visibility.update(false)) // one finishes, the other remains
        assertTrue(visibility.hidden)
        assertTrue(visibility.update(false)) // the second finishes
        assertFalse(visibility.hidden)
    }

    @Test
    fun threeOverlappingCapturesStayHiddenUntilLastRelease() {
        val visibility = CaptureVisibility()
        repeat(3) { visibility.update(true) }
        repeat(2) {
            assertFalse(visibility.update(false))
            assertTrue(visibility.hidden)
        }
        assertTrue(visibility.update(false))
        assertFalse(visibility.hidden)
    }

    @Test
    fun extraRestoreCannotMakeTheNextCaptureVisible() {
        val visibility = CaptureVisibility()
        assertFalse(visibility.update(false))
        assertFalse(visibility.update(false))
        assertTrue(visibility.update(true))
        assertTrue(visibility.hidden)
        assertTrue(visibility.update(false))
        assertFalse(visibility.hidden)
    }
}
