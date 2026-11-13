package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.decodeSessionSummary
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionInventoryTest {
    private fun row(id: String, title: String = id) =
        decodeSessionSummary(
            buildJsonObject {
                put("sessionId", id)
                putJsonObject("projections") { putJsonObject("values") { put("title", title) } }
            },
        )

    @Test
    fun largeRefreshKeepsConfirmedRenameDeleteAndNewSessionWhileSharingUnchangedRows() {
        val inventory = SessionInventory()
        val rows = (0 until 20_000).map { row("s$it") }
        inventory.replace(rows, inventory.beginRead())
        val staleRead = inventory.beginRead()
        val before = inventory.snapshot()
        inventory.renamed("s19999", "Host-confirmed title")
        assertSame(before.first(), inventory.snapshot().first())
        assertEquals("s19999", before.last().title)
        inventory.removed("s5")
        inventory.upsert(row("fork"))
        inventory.replace(rows, staleRead)
        val merged = inventory.snapshot()
        assertEquals(20_000, merged.size)
        assertEquals("fork", merged.first().sessionId)
        assertNull(inventory.get("s5"))
        assertEquals("Host-confirmed title", inventory.get("s19999")!!.title)
        assertSame(before[100], inventory.get("s100"))

        val fresh = merged.map {
            if (it.sessionId == "s19999") it.copy(title = "Changed on the Host") else it
        }
        inventory.replace(fresh, inventory.beginRead())
        assertEquals("Changed on the Host", inventory.get("s19999")!!.title)
    }
}
