package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.hosts.LinkState
import org.junit.Assert.*
import org.junit.Test

class AppStateLinkTest {
    @Test
    fun connectionLiveIsDerivedFromTheLinkOwnerState() {
        val offline = dev.fughetta.dshcompose.ui.AppState()
        assertFalse(offline.connectionLive)

        assertTrue(
            dev.fughetta.dshcompose.ui.AppState(linkState = LinkState.Online(null)).connectionLive,
        )
        assertFalse(
            dev.fughetta.dshcompose.ui.AppState(linkState = LinkState.Connecting(2)).connectionLive,
        )
        assertFalse(
            dev.fughetta.dshcompose.ui
                .AppState(linkState = LinkState.Failed("link/unauthorized"))
                .connectionLive,
        )
    }
}
