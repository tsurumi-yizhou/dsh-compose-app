package dev.fughetta.dshcompose.ui.engine_settings

import dev.fughetta.dshcompose.domain.engine.ClientSlotOccupant
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSeatsStateTest {
    private val occupants =
        persistentListOf(
            ClientSlotOccupant(
                slot = "main",
                packageName = "x",
                cell = "y",
                order = 0,
                label = null,
                official = false,
            ),
        )

    @Test
    fun hidesSeatsWithoutWebShell() {
        val state =
            PluginSeatsState(occupancyCurrent = true, webShell = false, occupants = occupants)
        assertTrue(state.seats(null).isEmpty())
    }

    @Test
    fun showsSeatsWithWebShellAndCurrentOccupancy() {
        val state =
            PluginSeatsState(occupancyCurrent = true, webShell = true, occupants = occupants)
        val seats = state.seats(null)
        assertEquals(1, seats.size)
        assertEquals("main", seats.single().slot)
    }

    @Test
    fun hidesSeatsWithoutCurrentOccupancy() {
        val state =
            PluginSeatsState(occupancyCurrent = false, webShell = true, occupants = occupants)
        assertTrue(state.seats(null).isEmpty())
    }
}
