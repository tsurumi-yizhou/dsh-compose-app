package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.collections.immutable.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPromptsTest {
    private fun inbox(id: String) =
        PendingInboxUi(
            InboxItem(
                id,
                InboxSlot.NextTurn,
                id,
                null,
                "user",
                null,
                true,
            ),
            canRestore = true,
        )

    @Test
    fun collapsedSummaryCountsLocalAndInboxRows() {
        val owner = PendingPromptsOwner("h", "home", "s")
        assertEquals(0, PendingPromptsUiState(owner).itemCount())
        val state =
            PendingPromptsUiState(
                owner,
                pending =
                    persistentListOf(
                        PendingPromptUi("r1", PendingPromptUiPhase.Sending, "a", null),
                        PendingPromptUi("r2", PendingPromptUiPhase.Rejected, "b", null),
                    ),
                inbox = persistentListOf(inbox("i1")),
                error = "boom",
            )
        assertEquals(3, state.itemCount())
    }
}
