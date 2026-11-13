package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class BackStackHomeTest {
    private data object Page : NavKey

    private val home = Chat(DRAFT_SESSION_ID)

    @Test
    fun hostChangeLeavingOnlyAPageRootsItAtHome() {
        // [Chat, SSH hosts] after connecting a host: the chat was dropped with the old host.
        val stack = mutableListOf<NavKey>(Page)
        rootAtHome(stack, home, expanded = false)
        assertEquals(listOf(home, Page), stack)
    }

    @Test
    fun expandedKeepsTheListAheadOfHome() {
        val stack = mutableListOf<NavKey>(Conversations, Page)
        rootAtHome(stack, home, expanded = true)
        assertEquals(listOf(Conversations, home, Page), stack)
    }

    @Test
    fun finishedSetupRootGivesWayToHome() {
        // Setup's remote choice opens SSH hosts over the setup page.
        val stack = mutableListOf<NavKey>(Engine, Page)
        rootAtHome(stack, home, expanded = false)
        assertEquals(listOf(home, Page), stack)
    }

    @Test
    fun stackThatAlreadyLeadsHomeIsLeftAlone() {
        val stack = mutableListOf<NavKey>(Chat("a"), Engine, Page)
        rootAtHome(stack, home, expanded = false)
        assertEquals(listOf(Chat("a"), Engine, Page), stack)
    }
}
