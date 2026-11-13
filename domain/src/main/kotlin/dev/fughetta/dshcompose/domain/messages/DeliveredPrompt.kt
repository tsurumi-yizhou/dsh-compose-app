package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration

/** Delivery may clear the matching composer only while its host generation is still selected. */
data class DeliveredPrompt(val prompt: PendingPrompt, val generation: ConnectionGeneration)
