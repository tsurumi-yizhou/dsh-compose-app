package dev.fughetta.dshcompose.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTierTest {
    @Test
    fun breakpointsFollowTheMaterialWidthClasses() {
        assertEquals(WindowTier.Compact, windowTierFor(0))
        assertEquals(WindowTier.Compact, windowTierFor(599))
        assertEquals(WindowTier.Medium, windowTierFor(600))
        assertEquals(WindowTier.Medium, windowTierFor(839))
        assertEquals(WindowTier.Expanded, windowTierFor(840))
        assertEquals(WindowTier.Expanded, windowTierFor(1199))
        assertEquals(WindowTier.Large, windowTierFor(1200))
        assertEquals(WindowTier.Large, windowTierFor(1599))
        assertEquals(WindowTier.ExtraLarge, windowTierFor(1600))
        assertEquals(WindowTier.ExtraLarge, windowTierFor(2960))
    }

    @Test
    fun tiersOrderFromNarrowToWide() {
        assertTrue(WindowTier.Compact < WindowTier.Medium)
        assertTrue(WindowTier.Medium.atLeastMedium)
        assertTrue(!WindowTier.Compact.atLeastMedium)
        assertTrue(WindowTier.ExtraLarge.atLeastLarge)
        assertTrue(!WindowTier.Expanded.atLeastLarge)
    }

    @Test
    fun contentCapsGrowFromReadingToTables() {
        assertTrue(ContentWidths.Reading < ContentWidths.Form)
        assertTrue(ContentWidths.Form < ContentWidths.Board)
        assertTrue(ContentWidths.Board < ContentWidths.Wide)
    }
}
