package dev.fughetta.dshcompose.domain.hosts

import dev.fughetta.dshcompose.domain.account.AccountRepository
import dev.fughetta.dshcompose.domain.notify.NotificationsRepository
import kotlinx.coroutines.flow.StateFlow

class LocalHostLease(
    val generation: ConnectionGeneration,
    val account: AccountRepository?,
    val notifications: NotificationsRepository,
)

class LocalHostObservation(private val current: StateFlow<LocalHostLease?>) {
    val lease: LocalHostLease?
        get() = current.value
}
