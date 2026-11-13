package dev.fughetta.dshcompose.domain.usage

import dev.fughetta.dshcompose.domain.chat.ToolPayload

sealed interface UsageChange {
    data object Reset : UsageChange

    data class Snapshot(val projections: ToolPayload) : UsageChange

    data class Record(val type: String) : UsageChange
}
