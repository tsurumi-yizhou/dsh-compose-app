package dev.fughetta.dshcompose.notify

import dev.fughetta.dshcompose.ui.notify.NowBarState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Samsung's second gate over live activities: Android promoting the notification is not enough for
 * the Now Bar, so the settings page has to say which of the two it is waiting on.
 */
class NowBarTest {
    @Test
    fun saysNothingWhereThereIsNoNowBar() {
        assertEquals(NowBarState.NotApplicable, NowBar.state(relevant = false, allowed = null))
        assertEquals(NowBarState.NotApplicable, NowBar.state(relevant = false, allowed = true))
    }

    @Test
    fun readsTheDeveloperOptionAsTheGate() {
        assertEquals(NowBarState.Blocked, NowBar.state(relevant = true, allowed = false))
        assertEquals(NowBarState.Allowed, NowBar.state(relevant = true, allowed = true))
    }

    @Test
    fun anUnreadableSwitchIsNotTheSameAsABlockedOne() {
        assertEquals(NowBarState.Unknown, NowBar.state(relevant = true, allowed = null))
    }

    @Test
    fun survivesTheTripOverTheBridge() {
        NowBarState.values().forEach { assertEquals(it, NowBar.parse(it.name)) }
        assertEquals(NowBarState.NotApplicable, NowBar.parse(null))
        assertEquals(NowBarState.NotApplicable, NowBar.parse("Allowed "))
    }
}
