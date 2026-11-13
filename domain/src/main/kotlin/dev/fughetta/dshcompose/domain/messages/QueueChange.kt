package dev.fughetta.dshcompose.domain.messages

sealed interface QueueChange {
    data class Edit(val text: String) : QueueChange

    data object Remove : QueueChange

    data object Steer : QueueChange
}
